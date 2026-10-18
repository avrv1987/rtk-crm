package ru.rtk.crm.max;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import ru.rtk.crm.access.CrmProfile;

// Фильтр цепочки /api/max/app/**: токен из заголовка Bearer → профиль CRM. Не бин Spring: иначе Spring Boot
// зарегистрировал бы его общим фильтром для всех запросов.
final class MaxSessionFilter extends OncePerRequestFilter {
    private static final String PREFIX = "Bearer ";

    private final MaxSessionService sessions;

    MaxSessionFilter(MaxSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(PREFIX) && header.length() > PREFIX.length()) {
            String token = header.substring(PREFIX.length()).strip();
            Optional<CrmProfile> profile = sessions.authenticate(token);
            profile.ifPresent(value -> SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(new MaxPrincipal(value, token), null, List.of())));
        }
        chain.doFilter(request, response);
    }

    public record MaxPrincipal(CrmProfile profile, String token) {
        @Override
        public String toString() {
            return profile.id().toString();
        }
    }
}
