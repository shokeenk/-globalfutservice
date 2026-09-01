package com.globalfutservice.web;

/**
 * Application exceptions that map cleanly onto HTTP.
 *
 * <p>Each carries a message written to be shown to a customer. None of them ever
 * interpolates an internal identifier, a SQL fragment or a class name — an error body is
 * a reconnaissance surface, and "no such column orders.credential_ciphertext" tells an
 * attacker more than they could learn in an hour of guessing.
 */
public final class ApiExceptions {

    private ApiExceptions() {
    }

    /** 404. Also used where 403 would confirm that somebody else's resource exists. */
    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    /** 400 — the caller can fix this. */
    public static class BadRequestException extends RuntimeException {
        private final String code;

        public BadRequestException(String message) {
            this("bad_request", message);
        }

        public BadRequestException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 409 — the caller's view of the world is stale; re-read and retry. */
    public static class ConflictException extends RuntimeException {
        private final String code;

        public ConflictException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** 403 — authenticated, but not allowed. */
    public static class ForbiddenException extends RuntimeException {
        public ForbiddenException(String message) {
            super(message);
        }
    }

    /** 502 — an upstream we depend on misbehaved. */
    public static class UpstreamException extends RuntimeException {
        public UpstreamException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
