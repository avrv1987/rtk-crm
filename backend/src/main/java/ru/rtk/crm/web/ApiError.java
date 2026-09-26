package ru.rtk.crm.web;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(
        String code,
        String message,
        String requestId,
        Map<String, String> fieldErrors,
        Integer currentVersion
) {
    public static ApiError of(String code, String message, String requestId) {
        return new ApiError(code, message, requestId, null, null);
    }
}
