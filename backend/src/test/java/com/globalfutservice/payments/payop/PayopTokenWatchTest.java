package com.globalfutservice.payments.payop;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.PaymentAlert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PayopTokenWatchTest {

    private final NotificationService notifications = mock(NotificationService.class);
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-04T06:00:00Z"));
    private final Clock clock = new Clock() {
        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    };

    private PayopTokenWatch watch(boolean enabled, String expires) {
        AppProperties props = mock(AppProperties.class);
        when(props.payop()).thenReturn(PayopStartupCheckTest.payop(enabled, "pub", "secret", "jwt", "app", expires));
        return new PayopTokenWatch(props, notifications, clock);
    }

    private PaymentAlert alerted() {
        ArgumentCaptor<PaymentAlert> alert = ArgumentCaptor.forClass(PaymentAlert.class);
        verify(notifications).paymentAlert(alert.capture());
        return alert.getValue();
    }

    @Test
    @DisplayName("more than a week left: quiet")
    void quietWhileFar() {
        watch(true, "2026-10-12T06:00:00Z").checkExpiry();
        watch(true, null).checkExpiry();
        verify(notifications, never()).paymentAlert(any());
    }

    @Test
    @DisplayName("inside the last week: staff are told how many days remain, and what to do")
    void lastWeek() {
        watch(true, "2026-10-10T07:00:00Z").checkExpiry();
        PaymentAlert alert = alerted();
        assertThat(alert.headline()).isEqualTo("Payop token expires in 6 days");
        assertThat(alert.code()).isEqualTo("TOKEN_EXPIRING");
        assertThat(alert.publicRef()).isNull();
        assertThat(alert.detail()).contains("GFS_PAYOP_JWT_TOKEN").doesNotContain("jwt ");
    }

    @Test
    @DisplayName("on the day, and after it")
    void dayOfAndAfter() {
        watch(true, "2026-10-04T20:00:00Z").checkExpiry();
        assertThat(alerted().headline()).isEqualTo("Payop token expires today");
        watch(true, "2026-10-03").checkExpiry();
        verify(notifications, times(2)).paymentAlert(any());
    }

    @Test
    @DisplayName("an expired token is called expired")
    void expired() {
        watch(true, "2026-10-01").checkExpiry();
        assertThat(alerted().code()).isEqualTo("TOKEN_EXPIRED");
    }

    @Test
    @DisplayName("while Payop is off, nothing is checked")
    void off() {
        watch(false, "2026-10-01").checkExpiry();
        verify(notifications, never()).paymentAlert(any());
    }

    @Test
    @DisplayName("a refused token alerts at once, then at most every twelve hours")
    void refusedIsRateLimited() {
        PayopTokenWatch watch = watch(true, null);
        watch.refused("get transaction");
        watch.refused("list methods");
        now.set(now.get().plus(PayopTokenWatch.REPEAT).minusSeconds(1));
        watch.refused("get transaction");
        PaymentAlert first = alerted();
        assertThat(first.code()).isEqualTo("TOKEN_REJECTED");
        assertThat(first.detail()).contains("get transaction");

        now.set(now.get().plusSeconds(1));
        watch.refused("list methods");
        verify(notifications, times(2)).paymentAlert(any());
    }
}
