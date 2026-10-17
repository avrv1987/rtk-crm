package ru.rtk.crm.security;

import java.io.IOException;
import java.time.Duration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.filter.OncePerRequestFilter;

// Абсолютный срок сессии CRM: сессия старше срока закрывается независимо от активности, и следующий запрос
// требует нового входа через Keycloak с новым ID-токеном. Так роль, отключение или блокировка, сделанные
// в консоли Keycloak, доходят до CRM не позже этого срока, даже если сотрудник работает без перерывов.
public class SessionLifetimeFilter extends OncePerRequestFilter {
    private final long maxAgeMillis;

    public SessionLifetimeFilter(Duration maxAge) {
        this.maxAgeMillis = maxAge.toMillis();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session != null && System.currentTimeMillis() - session.getCreationTime() > maxAgeMillis) {
            session.invalidate();
        }
        chain.doFilter(request, response);
    }
}
