package com.globalfutservice.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The one error shape this API ever returns.
 *
 * @param error     stable machine-readable code the frontend switches on
 * @param message   text safe to render to a customer verbatim
 * @param details   field-level validation messages, keyed by field name
 * @param traceId   correlates this response with the server log line
 */
public record ApiError(
        String error,
        String message,
        Map<String, List<String>> details,
        Instant timestamp,
        String traceId) {

    public static ApiError of(String error, String message, String traceId) {
        return new ApiError(error, message, null, Instant.now(), traceId);
    }

    public static ApiError of(String error, String message,
                              Map<String, List<String>> details, String traceId) {
        return new ApiError(error, message, details, Instant.now(), traceId);
    }
}
