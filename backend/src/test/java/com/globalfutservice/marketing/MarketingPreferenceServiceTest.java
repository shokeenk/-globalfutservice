package com.globalfutservice.marketing;

import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Turning promotional consent on and off from a signed-in session.
 *
 * <p>This is the only path in the application that can grant consent after registration,
 * so the tests are mostly about the timestamp: "when did this customer agree" is the
 * question a consent record exists to answer, and it stops being answerable the moment
 * something overwrites it on an unrelated save.
 */
class MarketingPreferenceServiceTest {

    private AccountRepository accounts;
    private MarketingPreferenceService service;
    private AccountEntity account;

    @BeforeEach
    void setUp() {
        accounts = mock(AccountRepository.class);
        service = new MarketingPreferenceService(accounts);
        account = new AccountEntity("acc_test", "player@example.com", "hash",
                AccountRole.CUSTOMER);
        when(accounts.findById(1L)).thenReturn(Optional.of(account));
    }

    @Test
    @DisplayName("a new account has not agreed to anything")
    void defaultsToOff() {
        assertThat(service.isOptedIn(1L)).isFalse();
    }

    @Test
    @DisplayName("opting in records when they agreed")
    void optingInStampsTheTime() {
        Instant before = Instant.now();

        assertThat(service.set(1L, true)).isTrue();

        assertThat(account.isMarketingOptIn()).isTrue();
        assertThat(account.getMarketingOptInAt()).isNotNull()
                .isAfterOrEqualTo(before);
        verify(accounts).save(account);
    }

    @Test
    @DisplayName("opting out records when they left, and clears the agreement")
    void optingOutStampsTheTime() {
        service.set(1L, true);

        assertThat(service.set(1L, false)).isFalse();

        assertThat(account.isMarketingOptIn()).isFalse();
        assertThat(account.getMarketingOptOutAt()).isNotNull();
    }

    @Test
    @DisplayName("opting out from the account page blames no campaign")
    void accountPageOptOutHasNoCampaign() {
        service.set(1L, true);
        service.set(1L, false);

        assertThat(account.getMarketingOptOutCampaignId()).isNull();
    }

    @Test
    @DisplayName("re-saving the same answer does not move the consent date")
    void repeatIsNotAFreshConsent() {
        service.set(1L, true);
        Instant agreedAt = account.getMarketingOptInAt();

        service.set(1L, true);

        assertThat(account.getMarketingOptInAt()).isEqualTo(agreedAt);
    }

    @Test
    @DisplayName("turning off something already off writes nothing at all")
    void noopDoesNotWrite() {
        assertThat(service.set(1L, false)).isFalse();

        verify(accounts, never()).save(any());
    }

    @Test
    @DisplayName("opting back in clears the earlier opt-out")
    void returningClearsTheOptOut() {
        service.set(1L, true);
        service.set(1L, false);

        service.set(1L, true);

        assertThat(account.isMarketingOptIn()).isTrue();
        assertThat(account.getMarketingOptOutAt()).isNull();
    }

    @Test
    @DisplayName("an unknown account is not silently treated as opted out")
    void unknownAccountFails() {
        when(accounts.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.set(99L, true))
                .isInstanceOf(ApiExceptions.NotFoundException.class);
    }
}
