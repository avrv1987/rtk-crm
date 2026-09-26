package ru.rtk.crm.security;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;
import ru.rtk.crm.web.ApiError;

@Component
public class ApiErrorLog {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiErrorLog.class);

    public void record(HttpServletRequest request, int status, ApiError error, String reason) {
        if (status < 400 || status >= 500) {
            return;
        }
        LOGGER.info(
                "API error status={} code={} requestId={} user={} operation={} {} reason={}",
                status,
                error.code(),
                error.requestId(),
                user(),
                request.getMethod(),
                path(request),
                reason
        );
    }

    public static String reasonOf(ApiError error) {
        return error.fieldErrors() == null || error.fieldErrors().isEmpty()
                ? error.message()
                : error.message() + " " + error.fieldErrors().keySet();
    }

    private static String path(HttpServletRequest request) {
        Object original = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        return original instanceof String uri ? uri : request.getRequestURI();
    }

    private static String user() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof OidcUser user) {
            return user.getSubject();
        }
        return "anonymous";
    }
}
