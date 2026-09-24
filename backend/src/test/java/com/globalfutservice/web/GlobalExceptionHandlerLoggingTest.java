package com.globalfutservice.web;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a 500 leaves behind.
 *
 * <p>A production 500 read {@code [dbb07681] Unhandled exception} and nothing more, which
 * is not enough to act on. The stack trace was in fact being handed to the logger all
 * along — SLF4J prints one when the trailing argument is a throwable with no placeholder
 * of its own — but it arrives as continuation lines with no timestamp and no logger name,
 * and it is easy to lose between the log viewer and the reader.
 *
 * <p>So both halves are pinned here: the throwable still reaches the logger, and the
 * first line names the exception without needing the lines under it. The first is what
 * a careless edit drops; the second is what makes the line survive a log pipeline.
 */
class GlobalExceptionHandlerLoggingTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final Logger logger =
            (Logger) LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void captureLogs() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("hands the throwable to the logger, so a stack trace is printed")
    void logsStackTrace() {
        handler.unexpected(new IllegalStateException("mail sender is not configured"));

        ILoggingEvent event = only();
        assertThat(event.getLevel()).isEqualTo(Level.ERROR);
        assertThat(event.getThrowableProxy()).isNotNull();
        assertThat(event.getThrowableProxy().getClassName())
                .isEqualTo(IllegalStateException.class.getName());
        assertThat(event.getThrowableProxy().getStackTraceElementProxyArray()).isNotEmpty();
    }

    @Test
    @DisplayName("names the exception on the first line, without the lines under it")
    void firstLineStandsAlone() {
        handler.unexpected(new IllegalStateException("mail sender is not configured"));

        assertThat(only().getFormattedMessage())
                .contains("java.lang.IllegalStateException")
                .contains("mail sender is not configured");
    }

    @Test
    @DisplayName("keeps the reference id in the log line and in the reply, and they match")
    void referenceIsShared() {
        ResponseEntity<ApiError> response = handler.unexpected(new RuntimeException("boom"));

        // The reference is the whole mechanism: it is the only thing a customer can quote
        // that leads an operator to the trace. A line and a reply carrying different ones
        // would be worse than neither.
        String reference = response.getBody().traceId();
        assertThat(reference).isNotBlank();
        assertThat(only().getFormattedMessage()).contains("[" + reference + "]");
    }

    @Test
    @DisplayName("tells the caller nothing but the reference")
    void replyStaysGeneric() {
        ResponseEntity<ApiError> response =
                handler.unexpected(new IllegalStateException("password=hunter2 rejected"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ApiError body = response.getBody();
        assertThat(body.error()).isEqualTo("internal_error");
        assertThat(body.message())
                .doesNotContain("hunter2")
                .doesNotContain("IllegalStateException")
                .contains(body.traceId());
    }

    private ILoggingEvent only() {
        assertThat(appender.list).hasSize(1);
        return appender.list.get(0);
    }
}
