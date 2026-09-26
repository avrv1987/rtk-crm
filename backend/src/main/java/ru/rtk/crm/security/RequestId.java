package ru.rtk.crm.security;

import jakarta.servlet.http.HttpServletRequest;

public final class RequestId {
    public static final String ATTRIBUTE = RequestId.class.getName();

    private RequestId() {
    }

    public static String from(HttpServletRequest request) {
        Object value = request.getAttribute(ATTRIBUTE);
        return value instanceof String requestId ? requestId : "unknown";
    }
}
