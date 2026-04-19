package com.cravero.cravbank.common;

import java.time.Instant;
import java.util.List;

public record ApiError(
        Instant timestamp,
        int status,
        String code,
        String message,
        String path,
        List<FieldError> details
) {
    public record FieldError(String field, String message) {
    }

    public static ApiError of(int status, ErrorCode code, String message, String path) {
        return new ApiError(Instant.now(), status, code.name(), message, path, List.of());
    }

    public static ApiError of(int status, ErrorCode code, String message, String path, List<FieldError> details) {
        return new ApiError(Instant.now(), status, code.name(), message, path, details);
    }
}
