package com.globalfutservice.fulfilment;

import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** A tracking link that could not be had is asked for again 1, 2, 4, 8... minutes on, at most hourly. */
class PartnerTrackingLinksBackoffTest {

    @Test
    @DisplayName("doubles from a minute, and never waits more than an hour")
    void backoff() {
        assertThat(PartnerTrackingLinks.backoff(1)).isEqualTo(Duration.ofMinutes(1));
        assertThat(PartnerTrackingLinks.backoff(2)).isEqualTo(Duration.ofMinutes(2));
        assertThat(PartnerTrackingLinks.backoff(3)).isEqualTo(Duration.ofMinutes(4));
        assertThat(PartnerTrackingLinks.backoff(4)).isEqualTo(Duration.ofMinutes(8));
        assertThat(PartnerTrackingLinks.backoff(7)).isEqualTo(Duration.ofMinutes(60));
        assertThat(PartnerTrackingLinks.backoff(500)).isEqualTo(Duration.ofMinutes(60));
        assertThat(PartnerTrackingLinks.backoff(0)).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    @DisplayName("the call log keeps our own codes, and masks anything that looks like the link")
    void masked() {
        assertThat(VendorCallLog.masked("https://transferstatus.top/abc12345")).isEqualTo("MASKED");
        assertThat(VendorCallLog.masked("see transferstatus.top")).isEqualTo("MASKED");
        assertThat(VendorCallLog.masked("www.example.test")).isEqualTo("MASKED");
        assertThat(VendorCallLog.masked("ORDER_FINISHED")).isEqualTo("ORDER_FINISHED");
        assertThat(VendorCallLog.masked(null)).isNull();
    }
}
