package ru.rtk.crm.mcp;

import java.io.IOException;
import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.rtk.crm.access.CrmProfile;
import ru.rtk.crm.access.CrmProfileNotFoundException;
import ru.rtk.crm.access.CurrentProfileService;
import ru.rtk.crm.access.UserProfileRepository;
import ru.rtk.crm.access.UserRole;
import ru.rtk.crm.security.ApiErrorWriter;
import ru.rtk.crm.security.RequestId;

// Стоит после проверки токена: превращает действующий access token в профиль CRM и решает, пускать ли его в MCP.
// Права на данные дальше проверяют сами сервисы CRM; здесь только «кто это» и частота.
final class McpAccessFilter extends OncePerRequestFilter {
    // Анкеты слушателей, админка и кабинет вуза в MCP не публикуются, поэтому этим профилям нечего делать в /mcp.
    private static final Set<UserRole> ALLOWED_ROLES = Set.of(UserRole.USER, UserRole.LEADER, UserRole.MANAGEMENT);
    private static final long WINDOW_MILLIS = 60_000;

    private final CurrentProfileService currentProfileService;
    private final UserProfileRepository userProfileRepository;
    private final ApiErrorWriter apiErrorWriter;
    private final int callsPerMinute;
    private final Clock clock;
    private final Map<UUID, long[]> windows = new ConcurrentHashMap<>();

    McpAccessFilter(
            CurrentProfileService currentProfileService,
            UserProfileRepository userProfileRepository,
            ApiErrorWriter apiErrorWriter,
            int callsPerMinute,
            Clock clock
    ) {
        this.currentProfileService = currentProfileService;
        this.userProfileRepository = userProfileRepository;
        this.apiErrorWriter = apiErrorWriter;
        this.callsPerMinute = callsPerMinute;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken token)) {
            chain.doFilter(request, response);
            return;
        }
        Jwt jwt = token.getToken();
        CrmProfile profile;
        try {
            profile = currentProfileService.requireTokenProfile(jwt.getIssuer().toString(), jwt.getSubject(), roles(jwt));
        } catch (CrmProfileNotFoundException exception) {
            apiErrorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, "CRM_PROFILE_REQUIRED",
                    "Нужен активный профиль CRM с ролью, подтверждённой Keycloak", "mcp profile");
            return;
        }
        if (!ALLOWED_ROLES.contains(profile.role())) {
            apiErrorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "MCP доступен КАМ, руководителю команды и «Руководству»", "mcp role " + profile.role());
            return;
        }
        // Роль подходит, а отказ из-за флага: называем причину, иначе КАМ не поймёт, почему его не пускают.
        if (userProfileRepository.isEnrolmentOperator(profile.id())) {
            apiErrorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN",
                    "MCP закрыт: у вас отметка «Менеджер учебного процесса» — анкеты слушателей не передаются ИИ-агентам. "
                            + "Снять отметку может администратор", "mcp enrolment operator");
            return;
        }
        if (!acquire(profile.id())) {
            response.setHeader("Retry-After", "60");
            apiErrorWriter.write(request, response, 429, "TOO_MANY_REQUESTS",
                    "Слишком много запросов к MCP: не больше " + callsPerMinute + " в минуту", "mcp rate limit");
            return;
        }
        String scope = jwt.getClaimAsString("scope");
        Set<String> scopes = scope == null || scope.isBlank() ? Set.of() : Set.copyOf(List.of(scope.strip().split("\\s+")));
        request.setAttribute(McpCaller.ATTRIBUTE,
                new McpCaller(profile, jwt.getClaimAsString("azp"), scopes, RequestId.from(request)));
        chain.doFilter(request, response);
    }

    // ponytail: окно в памяти процесса — верно, пока backend один; при нескольких экземплярах счётчик нужен общий.
    private boolean acquire(UUID profileId) {
        long now = clock.millis();
        long[] window = windows.compute(profileId, (id, current) ->
                current == null || now - current[0] >= WINDOW_MILLIS ? new long[]{now, 1} : new long[]{current[0], current[1] + 1});
        return window[1] <= callsPerMinute;
    }

    private static Collection<?> roles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        return realmAccess != null && realmAccess.get("roles") instanceof Collection<?> names ? names : List.of();
    }
}
