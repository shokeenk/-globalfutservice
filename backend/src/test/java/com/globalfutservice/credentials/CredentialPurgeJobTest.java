package com.globalfutservice.credentials;

import java.time.Duration;
import java.util.List;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.scheduling.SchedulerLock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The sweep keeps both promises: expired sign-ins, and sign-ins held for review too long. */
class CredentialPurgeJobTest {

    @Test
    @DisplayName("one sweep, under the lock, purges the expired and the long-held-for-review")
    void sweep() {
        CredentialVaultService vault = mock(CredentialVaultService.class);
        SchedulerLock lock = mock(SchedulerLock.class);
        when(lock.runExclusively(eq("credential-purge"), any())).thenAnswer(inv -> {
            inv.<Runnable>getArgument(1).run();
            return true;
        });
        VendorOrderLedger ledger = mock(VendorOrderLedger.class);
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.futTransfer().reviewCredentialRetention()).thenReturn(Duration.ofHours(72));
        when(ledger.heldForReviewLongerThan(Duration.ofHours(72))).thenReturn(List.of(11L, 12L));

        new CredentialPurgeJob(vault, lock, ledger, props).sweep();

        verify(vault).purgeExpired();
        verify(vault).purge(11L, "held for review past retention");
        verify(vault).purge(12L, "held for review past retention");
    }
}
