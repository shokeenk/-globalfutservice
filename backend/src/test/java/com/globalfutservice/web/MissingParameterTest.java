package com.globalfutservice.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that forgot a field is the caller's mistake, and has to be answered as one.
 *
 * <p>Unmapped, Spring's "required parameter is not present" reached the catch-all: the
 * caller was told the server had broken and the log recorded an unhandled exception. The
 * status is the part a client acts on -- 400 says "fix the request", 500 says "try again
 * later" -- and the log is the part an operator acts on, where a drawer full of fake
 * server errors is where a real one goes unnoticed.
 */
class MissingParameterTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("a missing query parameter is 400, and names the field")
    void missingParameterIsBadRequest() {
        ResponseEntity<ApiError> response = handler.missingParameter(
                new MissingServletRequestParameterException("email", "String"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("invalid_request");
        assertThat(response.getBody().message()).contains("email");
    }

    @Test
    @DisplayName("a missing multipart part is 400 too -- the screenshot upload sends two")
    void missingPartIsBadRequest() {
        ResponseEntity<ApiError> response = handler.missingParameter(
                new MissingServletRequestPartException("file"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).contains("file");
    }
}
