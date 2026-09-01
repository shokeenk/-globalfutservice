package com.globalfutservice.web;

import com.globalfutservice.domain.orders.IllegalTransitionException;
import com.globalfutservice.domain.pricing.PricingException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns every exception into the same JSON shape, and decides what the caller is allowed
 * to learn.
 *
 * <p>The rule enforced here: <b>anything unexpected returns a generic message and a trace
 * id.</b> The detail goes to the log, where an operator can find it by trace id. Echoing
 * a stack trace or a constraint name into a response tells an attacker the schema, the
 * ORM, and often the exact query — for no benefit to a legitimate user, who cannot act
 * on it either way.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiExceptions.NotFoundException.class)
    public ResponseEntity<ApiError> notFound(ApiExceptions.NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found", e.getMessage(), traceId()));
    }

    @ExceptionHandler(ApiExceptions.BadRequestException.class)
    public ResponseEntity<ApiError> badRequest(ApiExceptions.BadRequestException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of(e.code(), e.getMessage(), traceId()));
    }

    @ExceptionHandler(ApiExceptions.ConflictException.class)
    public ResponseEntity<ApiError> conflict(ApiExceptions.ConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of(e.code(), e.getMessage(), traceId()));
    }

    @ExceptionHandler({ApiExceptions.ForbiddenException.class, AccessDeniedException.class})
    public ResponseEntity<ApiError> forbidden(Exception e) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(ApiError.of("forbidden", "You do not have access to this.", traceId()));
    }

    /** Pricing messages are written for customers, so they pass through verbatim. */
    @ExceptionHandler(PricingException.class)
    public ResponseEntity<ApiError> pricing(PricingException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("pricing_rejected", e.getMessage(), traceId()));
    }

    @ExceptionHandler(IllegalTransitionException.class)
    public ResponseEntity<ApiError> transition(IllegalTransitionException e) {
        String trace = traceId();
        log.warn("[{}] Rejected order transition {} -> {}", trace, e.from(), e.to());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiError.of("invalid_transition",
                        "That action is not available for this order any more. Refresh and try again.",
                        trace));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> validation(MethodArgumentNotValidException e) {
        Map<String, List<String>> details = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe ->
                details.computeIfAbsent(fe.getField(), k -> new ArrayList<>())
                        .add(fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(ApiError.of(
                "validation_failed", "Please check the highlighted fields.", details, traceId()));
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraint(ConstraintViolationException e) {
        return ResponseEntity.badRequest()
                .body(ApiError.of("validation_failed", "Please check your input.", traceId()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> unreadable(HttpMessageNotReadableException e) {
        // The parser's message quotes the offending JSON, which can contain whatever the
        // caller sent — including, on the credential endpoint, a password. Never echo it.
        return ResponseEntity.badRequest()
                .body(ApiError.of("malformed_request", "The request body could not be read.", traceId()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiError.of("not_found", "No such endpoint.", traceId()));
    }

    @ExceptionHandler(ApiExceptions.UpstreamException.class)
    public ResponseEntity<ApiError> upstream(ApiExceptions.UpstreamException e) {
        String trace = traceId();
        log.error("[{}] Upstream failure", trace, e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(ApiError.of(
                "upstream_unavailable",
                "We could not reach the payment provider. No money has moved — please try again.",
                trace));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception e) {
        String trace = traceId();
        log.error("[{}] Unhandled exception", trace, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(ApiError.of(
                "internal_error",
                "Something went wrong on our side. Quote this reference to support: " + trace,
                trace));
    }

    private static String traceId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
