package com.globalfutservice.payments.payop;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PayopStartupCheckTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);

    static AppProperties.Payop payop(boolean enabled, String publicKey, String secretKey, String jwt, String appId,
                                     String expires) {
        return new AppProperties.Payop(enabled, "https://api.payop.com", "https://checkout.payop.com", publicKey,
                secretKey, jwt, appId, expires, List.of("18.199.249.46"), List.of("173.245.48.0/20"),
                Duration.ofHours(1), Duration.ofHours(24), Duration.ofSeconds(15));
    }

    private static PayopStartupCheck check(AppProperties.Payop payop, boolean internationalPoints) {
        AppProperties props = mock(AppProperties.class);
        when(props.payop()).thenReturn(payop);
        when(props.loyalty()).thenReturn(new AppProperties.Loyalty(3, ZoneId.of("Asia/Kolkata"), internationalPoints));
        return new PayopStartupCheck(props, NOW);
    }

    @Test
    @DisplayName("off: nothing is required")
    void off() {
        assertThatCode(() -> check(payop(false, null, null, null, null, null), false).verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("on without its credentials: refuses to start, naming the variables and never a value")
    void onWithoutCredentials() {
        assertThatThrownBy(() -> check(payop(true, "application-pub", "", null, "  ", null), false).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GFS_PAYOP_SECRET_KEY")
                .hasMessageContaining("GFS_PAYOP_JWT_TOKEN")
                .hasMessageContaining("GFS_PAYOP_APPLICATION_ID")
                .hasMessageNotContaining("GFS_PAYOP_PUBLIC_KEY")
                .hasMessageNotContaining("application-pub");
    }

    @Test
    @DisplayName("on and configured: starts, with or without a token expiry")
    void onAndConfigured() {
        assertThatCode(() -> check(payop(true, "pub", "secret", "jwt", "app", null), false).verify())
                .doesNotThrowAnyException();
        assertThatCode(() -> check(payop(true, "pub", "secret", "jwt", "app", "2027-03-31"), false).verify())
                .doesNotThrowAnyException();
        // Within a week is logged, not refused: a running checkout beats a stopped one.
        assertThatCode(() -> check(payop(true, "pub", "secret", "jwt", "app", "2026-10-06"), false).verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("the token expiry reads as a date or an instant; anything else stops the start")
    void expiry() {
        assertThat(PayopStartupCheck.parseExpiry("2027-03-31")).isEqualTo(Instant.parse("2027-03-31T00:00:00Z"));
        assertThat(PayopStartupCheck.parseExpiry("2027-03-31T18:30:00Z")).isEqualTo(Instant.parse("2027-03-31T18:30:00Z"));
        assertThat(PayopStartupCheck.parseExpiry(" ")).isNull();
        assertThatThrownBy(() -> check(payop(true, "pub", "secret", "jwt", "app", "31/03/2027"), false).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GFS_PAYOP_JWT_EXPIRES_AT");
    }

    @Test
    @DisplayName("international points on stops the start, Payop or not")
    void internationalPoints() {
        assertThatThrownBy(() -> check(payop(false, null, null, null, null, null), true).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GFS_LOYALTY_INTERNATIONAL_POINTS");
    }

    @Test
    @DisplayName("the settings never print a credential")
    void redacted() {
        String text = payop(true, "application-pub", "s3cret-key", "eyJ.jwt.token", "app-42", null).toString();
        assertThat(text).doesNotContain("s3cret-key").doesNotContain("eyJ.jwt.token")
                .doesNotContain("application-pub").doesNotContain("app-42");
    }
}
