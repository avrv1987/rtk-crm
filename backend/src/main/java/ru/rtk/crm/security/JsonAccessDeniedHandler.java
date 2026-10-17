package ru.rtk.crm.security;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.stereotype.Component;

@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {
    private final ApiErrorWriter apiErrorWriter;

    public JsonAccessDeniedHandler(ApiErrorWriter apiErrorWriter) {
        this.apiErrorWriter = apiErrorWriter;
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException
    ) throws IOException, ServletException {
        boolean csrf = accessDeniedException instanceof CsrfException;
        apiErrorWriter.write(
                request,
                response,
                HttpServletResponse.SC_FORBIDDEN,
                csrf ? "CSRF_INVALID" : "FORBIDDEN",
                csrf ? "Страница устарела. Обновите её и повторите действие" : "Доступ запрещён",
                accessDeniedException.getClass().getSimpleName()
        );
    }
}
