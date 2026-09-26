package ru.rtk.crm.security;

import java.io.IOException;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import ru.rtk.crm.web.ApiError;

@Component
public class ApiErrorWriter {
    private final ObjectMapper objectMapper;
    private final ApiErrorLog apiErrorLog;

    public ApiErrorWriter(ObjectMapper objectMapper, ApiErrorLog apiErrorLog) {
        this.objectMapper = objectMapper;
        this.apiErrorLog = apiErrorLog;
    }

    public void write(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message,
            String reason
    ) throws IOException {
        ApiError error = ApiError.of(code, message, RequestId.from(request));
        apiErrorLog.record(request, status, error, reason);
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), error);
    }
}
