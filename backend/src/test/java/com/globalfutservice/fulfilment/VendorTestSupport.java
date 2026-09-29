package com.globalfutservice.fulfilment;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.web.CredentialDtos;
import org.slf4j.LoggerFactory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Shared test fixtures: configuration, a customer's sign-in, and a log capture. */
final class VendorTestSupport {

    /** Secrets that must never appear in a log line, a stored row or a response. */
    static final String RAW_KEY = "raw-api-key-issued-by-vendor-0001";
    static final String PASSWORD = "Hunter2-customer-password";
    static final List<String> BACKUP_CODES = List.of("11112222", "33334444");
    /** Backup codes the vendor's status response carries back. */
    static final List<String> RETURNED_CODES = List.of("99887766", "55443322");
    static final List<String> DOCUMENTED_CODES = List.of("MissingData", "InvalidPassword", "InvalidBA1",
            "InvalidBA2", "InvalidBA3", "InvalidBA4", "InvalidBA5", "InvalidAmount", "InvalidPlatform");

    /** GFS Transfer Method 3.0, exactly as the client's brief gives it. */
    static final AppProperties.FutTransferOrder METHOD_3_0 = new AppProperties.FutTransferOrder(
            300, 1, 50, 0, "1", 0, "0", "0", 0, "-1", "-1");

    private VendorTestSupport() {
    }

    /**
     * A backup domain nothing listens on. Never the vendor's real one: a test whose read
     * fails over must not reach the real API.
     */
    static final String NO_BACKUP = "http://127.0.0.1:9";

    static AppProperties props(String baseUrl, Duration timeout) {
        return props(baseUrl, NO_BACKUP, timeout);
    }

    static AppProperties props(String baseUrl, String backupUrl, Duration timeout) {
        AppProperties props = mock(AppProperties.class);
        when(props.futTransfer()).thenReturn(new AppProperties.FutTransfer(true, baseUrl, "api@example.test",
                RAW_KEY, "targetedSnipe", 1, Duration.ofSeconds(60), timeout, 3, DOCUMENTED_CODES,
                backupUrl, METHOD_3_0, true));
        when(props.publicUrl()).thenReturn("https://gfs.example.test");
        return props;
    }

    /** Calls not paused, and a record of any pause a test trips. */
    static VendorControl running() {
        return org.mockito.Mockito.mock(VendorControl.class);
    }

    static FutTransferClient client(AppProperties props, VendorControl control) {
        return new FutTransferClient(props, new com.fasterxml.jackson.databind.ObjectMapper(), control)
                .withoutRetryPauses();
    }

    static CredentialDtos.RevealedCredentials signIn() {
        return new CredentialDtos.RevealedCredentials("customer@example.test", PASSWORD, BACKUP_CODES, null, null);
    }

    /**
     * Every secret in play, including the digest actually sent as apiKey -- and the
     * customer's EA email, which goes to the vendor but never to a log line.
     */
    static List<String> secrets() {
        List<String> all = new java.util.ArrayList<>(List.of(RAW_KEY, FutTransferClient.md5(RAW_KEY), PASSWORD,
                "customer@example.test"));
        all.addAll(BACKUP_CODES);
        all.addAll(RETURNED_CODES);
        return all;
    }

    /** Everything logged anywhere while it is attached, as the text a log file would hold. */
    static final class LogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {
        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);

        LogCapture() {
            setContext(root.getLoggerContext());
            start();
            root.addAppender(this);
        }

        @Override
        protected void append(ILoggingEvent e) {
            StringBuilder line = new StringBuilder(e.getLoggerName()).append(' ').append(e.getFormattedMessage());
            if (e.getThrowableProxy() != null) {
                line.append(' ').append(e.getThrowableProxy().getClassName())
                        .append(": ").append(e.getThrowableProxy().getMessage());
            }
            lines.add(line.toString());
        }

        String all() {
            return String.join("\n", lines);
        }

        @Override
        public void close() {
            root.detachAppender(this);
            stop();
        }
    }
}
