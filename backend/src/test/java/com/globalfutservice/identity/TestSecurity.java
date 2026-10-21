package com.globalfutservice.identity;

import java.time.Duration;
import java.util.List;

import com.globalfutservice.config.AppProperties;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** The security settings application.yml ships, for tests that build the identity services by hand. */
final class TestSecurity {

    private TestSecurity() {
    }

    static AppProperties props() {
        return props(5, 20, 3, 100);
    }

    static AppProperties props(int failuresPerEmail, int failuresPerIp, int resetsPerEmail, int resetsPerIp) {
        AppProperties.Security security = new AppProperties.Security(
                "test-only-jwt-secret-000000000000000000000000000000",
                Duration.ofMinutes(15), Duration.ofDays(30),
                "test-only-quote-secret-00000000000000000000000000000",
                "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
                List.of("http://localhost:5173"), null, null, false,
                5, Duration.ofMinutes(15),
                Duration.ofHours(12),
                failuresPerEmail, failuresPerIp, Duration.ofMinutes(15),
                Duration.ofMinutes(30),
                resetsPerEmail, resetsPerIp, Duration.ofHours(1));
        AppProperties props = mock(AppProperties.class);
        when(props.security()).thenReturn(security);
        return props;
    }
}
