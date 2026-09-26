package ru.rtk.crm.source;

public class SourceFetchException extends RuntimeException {
    private final String code;

    SourceFetchException(String code, String message) {
        super(message);
        this.code = code;
    }

    static SourceFetchException unavailable(String message) {
        return new SourceFetchException("SOURCE_UNAVAILABLE", message);
    }

    static SourceFetchException unauthorized(String message) {
        return new SourceFetchException("SOURCE_UNAUTHORIZED", message);
    }

    static SourceFetchException invalidResponse(String message) {
        return new SourceFetchException("SOURCE_INVALID_RESPONSE", message);
    }

    String code() {
        return code;
    }
}
