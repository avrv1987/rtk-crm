package ru.rtk.crm.security;

import java.io.IOException;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

@Component
public class JsonAccessDeniedHandler implements AccessDeniedHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(JsonAccessDeniedHandler.class);

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
        LOGGER.warn("Denied API requestId={} reason={}", RequestId.from(request), accessDeniedException.getClass().getSimpleName());
        apiErrorWriter.write(request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "Доступ запрещён");
    }
}
