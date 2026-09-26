package com.globalfutservice.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpMediaTypeNotAcceptableException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request that asks for a format an endpoint does not produce.
 *
 * <p>This is the failure behind the campaign preview: the page sent
 * {@code Accept: application/json} to an endpoint that only produces HTML. It used to be
 * reported to the caller as a server error and logged as an unhandled exception, which
 * sent the diagnosis looking in the wrong place.
 */
class NotAcceptableTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("answers 406, not 500")
    void answers406() {
        ResponseEntity<ApiError> response = handler.notAcceptable(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_HTML)));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody().error()).isEqualTo("not_acceptable");
        assertThat(response.getBody().traceId()).isNotBlank();
    }

    @Test
    @DisplayName("fixes its own content type, so the error cannot itself be refused")
    void fixesContentType() {
        ResponseEntity<ApiError> response = handler.notAcceptable(
                new HttpMediaTypeNotAcceptableException(List.of(MediaType.TEXT_HTML)));

        // Left to negotiation, the same Accept header that caused the 406 would refuse
        // the JSON error body too, and the reply would become a 500 after all.
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
    }
}
