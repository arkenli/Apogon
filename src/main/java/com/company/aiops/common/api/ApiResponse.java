package com.company.aiops.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
        boolean success,
        String code,
        String message,
        T data,
        OffsetDateTime timestamp,
        String traceId
) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, "OK", "success", data, OffsetDateTime.now(), null);
    }

    public static <T> ApiResponse<T> ok(T data, String message) {
        return new ApiResponse<>(true, "OK", message, data, OffsetDateTime.now(), null);
    }

    public static ApiResponse<Void> error(String code, String message, String traceId) {
        return new ApiResponse<>(false, code, message, null, OffsetDateTime.now(), traceId);
    }
}
