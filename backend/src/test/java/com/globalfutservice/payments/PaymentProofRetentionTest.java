package com.globalfutservice.payments;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.orders.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * How long a payment screenshot is kept.
 *
 * <p>The query itself -- which rows are old enough, and which orders are exempt -- is
 * native SQL and is checked against a real Postgres, not here. What this pins is the part a
 * mock can: that the window comes from configuration rather than a constant, and is
 * measured back from the moment of the sweep.
 */
class PaymentProofRetentionTest {

    private static final Instant NOW = Instant.parse("2026-09-14T12:00:00Z");

    private ManualPaymentProofRepository proofs;
    private AppProperties.Fulfilment fulfilment;
    private ManualPaymentService service;

    @BeforeEach
    void setUp() {
        proofs = mock(ManualPaymentProofRepository.class);
        fulfilment = mock(AppProperties.Fulfilment.class);
        AppProperties props = mock(AppProperties.class);
        when(props.fulfilment()).thenReturn(fulfilment);
        when(fulfilment.proofRetention()).thenReturn(Duration.ofDays(90));

        service = new ManualPaymentService(
                mock(ManualPaymentClaimRepository.class), proofs, mock(OrderService.class),
                mock(CredentialVaultService.class), mock(NotificationService.class),
                mock(CustomerFeedService.class), props);
    }

    @Test
    @DisplayName("deletes what was uploaded more than ninety days before the sweep")
    void cutoffIsNinetyDaysBack() {
        when(proofs.deleteExpired(any())).thenReturn(3);

        assertThat(service.purgeExpiredProofs(NOW)).isEqualTo(3);
        verify(proofs).deleteExpired(Instant.parse("2026-06-16T12:00:00Z"));
    }

    @Test
    @DisplayName("takes the window from configuration, so it can change without a release")
    void windowIsConfigurable() {
        when(fulfilment.proofRetention()).thenReturn(Duration.ofDays(30));

        service.purgeExpiredProofs(NOW);
        verify(proofs).deleteExpired(Instant.parse("2026-08-15T12:00:00Z"));
    }

    @Test
    @DisplayName("a failing sweep is logged by the job, not thrown into the scheduler")
    void jobSwallowsFailure() {
        ManualPaymentService failing = mock(ManualPaymentService.class);
        when(failing.purgeExpiredProofs(any())).thenThrow(new IllegalStateException("db down"));

        // A thrown exception would not stop Spring's scheduler, but it would print a stack
        // trace every six hours instead of one line saying what went wrong.
        assertThatCode(() -> new PaymentProofPurgeJob(failing).sweep()).doesNotThrowAnyException();
    }
}
