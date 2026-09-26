package ru.rtk.crm.web;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import ru.rtk.crm.security.ApiErrorLog;

@RestControllerAdvice
public class ApiErrorLoggingAdvice implements ResponseBodyAdvice<Object> {
    private final ApiErrorLog apiErrorLog;

    public ApiErrorLoggingAdvice(ApiErrorLog apiErrorLog) {
        this.apiErrorLog = apiErrorLog;
    }

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public Object beforeBodyWrite(
            Object body,
            MethodParameter returnType,
            MediaType selectedContentType,
            Class<? extends HttpMessageConverter<?>> selectedConverterType,
            ServerHttpRequest request,
            ServerHttpResponse response
    ) {
        if (body instanceof ApiError error
                && request instanceof ServletServerHttpRequest servletRequest
                && response instanceof ServletServerHttpResponse servletResponse) {
            apiErrorLog.record(
                    servletRequest.getServletRequest(),
                    servletResponse.getServletResponse().getStatus(),
                    error,
                    ApiErrorLog.reasonOf(error)
            );
        }
        return body;
    }
}
