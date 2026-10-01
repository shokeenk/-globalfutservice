package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The admin's live view of the FUT Transfer balance: read at most once a minute, "unavailable" when it fails. */
class VendorBalanceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T06:00:00Z");

    @Test
    @DisplayName("read once, then served from memory for a minute, then read again")
    void cachedForAMinute() {
        FutTransferClient client = mock(FutTransferClient.class);
        when(client.balance(any())).thenReturn(new FutTransferClient.ReadOk<>(new BigDecimal("5000")),
                new FutTransferClient.ReadOk<>(new BigDecimal("4900")));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(T0, T0.plusSeconds(59), T0.plusSeconds(60));
        VendorBalance balance = new VendorBalance(client, clock);

        assertThat(balance.current().balance()).isEqualByComparingTo("5000");
        assertThat(balance.current().balance()).isEqualByComparingTo("5000");
        verify(client, times(1)).balance(any());

        VendorBalance.Reading later = balance.current();
        assertThat(later.balance()).isEqualByComparingTo("4900");
        assertThat(later.readAt()).isEqualTo(T0.plusSeconds(60));
        verify(client, times(2)).balance(any());
    }

    @Test
    @DisplayName("a failed read is unavailable, not zero, and is not retried on every refresh")
    void failureIsUnavailable() {
        FutTransferClient client = mock(FutTransferClient.class);
        when(client.balance(any())).thenReturn(
                new FutTransferClient.ReadFailed<>(FutTransferClient.ReadError.TRANSIENT, "TIMEOUT"));
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(T0, T0.plusSeconds(10));
        VendorBalance balance = new VendorBalance(client, clock);

        assertThat(balance.current().balance()).isNull();
        assertThat(balance.current().balance()).isNull();
        verify(client, times(1)).balance(any());
    }
}
