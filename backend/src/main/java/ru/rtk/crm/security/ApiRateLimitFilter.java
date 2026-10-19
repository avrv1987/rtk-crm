package ru.rtk.crm.security;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

// Ограничение частоты для /api/**: вошедший пользователь считается по своему входу (subject из Keycloak), остальные —
// по адресу клиента. Адрес берётся после ForwardedHeaderFilter (server.forward-headers-strategy), то есть тот, что
// передал nginx. Вебхук MAX не считается: его шлёт сервер MAX с одного адреса, а подлинность проверяет секрет модуля.
// /mcp и мини-приложение MAX сюда не попадают — у них свои цепочки и свои ограничения.
public final class ApiRateLimitFilter extends OncePerRequestFilter {
    private static final long WINDOW_MILLIS = 60_000;
    private static final int MAX_TRACKED = 50_000;

    private final ApiErrorWriter apiErrorWriter;
    private final int userPerMinute;
    private final int anonymousPerMinute;
    private final Clock clock;
    private final Map<String, long[]> windows = new ConcurrentHashMap<>();

    public ApiRateLimitFilter(ApiErrorWriter apiErrorWriter, int userPerMinute, int anonymousPerMinute, Clock clock) {
        this.apiErrorWriter = apiErrorWriter;
        this.userPerMinute = userPerMinute;
        this.anonymousPerMinute = anonymousPerMinute;
        this.clock = clock;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return !path.startsWith("/api/") || path.equals("/api/max/webhook");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean signedIn = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        int limit = signedIn ? userPerMinute : anonymousPerMinute;
        if (limit <= 0) {
            chain.doFilter(request, response);
            return;
        }
        long now = clock.millis();
        long[] window = acquire(signedIn ? "user:" + authentication.getName() : "ip:" + request.getRemoteAddr(), now);
        if (window[1] > limit) {
            long retryAfter = Math.max(1, (window[0] + WINDOW_MILLIS - now + 999) / 1000);
            response.setHeader("Retry-After", Long.toString(retryAfter));
            apiErrorWriter.write(request, response, 429, "RATE_LIMITED",
                    "Слишком много запросов, повторите через " + retryAfter + " сек.", "api rate limit");
            return;
        }
        chain.doFilter(request, response);
    }

    // ponytail: окно в памяти процесса — верно, пока backend один (как у MCP); при нескольких экземплярах счётчик
    // нужен общий (Redis или таблица), иначе каждый экземпляр пропустит свой предел.
    private long[] acquire(String key, long now) {
        if (windows.size() > MAX_TRACKED) {
            windows.values().removeIf(window -> now - window[0] >= WINDOW_MILLIS);
        }
        return windows.compute(key, (ignored, current) ->
                current == null || now - current[0] >= WINDOW_MILLIS ? new long[]{now, 1} : new long[]{current[0], current[1] + 1});
    }
}
