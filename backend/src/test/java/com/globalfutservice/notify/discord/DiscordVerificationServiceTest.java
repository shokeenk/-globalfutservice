package com.globalfutservice.notify.discord;

import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Who gets into a ticket, and who does not.
 *
 * <p>The assertions worth reading are the refusals. An order reference is printed on
 * receipts and turns up in the screenshots customers post publicly while asking for help,
 * so the interesting question is never "does a valid reference work" — it is what happens
 * when somebody types one that is not theirs.
 */
class DiscordVerificationServiceTest {

    private static final String REF = "GFS-26-BWG6NGG3";
    private static final String OWNER = "111111111111111111";
    private static final String STRANGER = "999999999999999999";
    private static final String CHANNEL = "555000555000";

    private OrderRepository orders;
    private DiscordVerificationRepository verifications;
    private DiscordAttemptRepository attempts;
    private DiscordBotClient bot;
    private AccountRepository accounts;
    private DiscordVerificationService service;

    private OrderEntity order;

    @BeforeEach
    void setUp() {
        orders = mock(OrderRepository.class);
        verifications = mock(DiscordVerificationRepository.class);
        attempts = mock(DiscordAttemptRepository.class);
        bot = mock(DiscordBotClient.class);
        accounts = mock(AccountRepository.class);
        service = new DiscordVerificationService(orders, verifications, attempts, bot, accounts);

        order = mock(OrderEntity.class);
        when(order.getId()).thenReturn(42L);
        when(orders.findByPublicRef(REF)).thenReturn(Optional.of(order));
        when(verifications.findByOrderId(42L)).thenReturn(Optional.empty());
        when(bot.isEnabled()).thenReturn(true);
        when(bot.findTicketChannel(REF)).thenReturn(Optional.of(CHANNEL));
    }

    @Nested
    @DisplayName("a customer verifying their own order")
    class HappyPath {

        @Test
        @DisplayName("is let into the ticket")
        void grantsAccess() {
            DiscordVerificationService.Result result = service.verify(OWNER, "player", REF);

            assertThat(result.granted()).isTrue();
            assertThat(result.channelId()).isEqualTo(CHANNEL);
            verify(bot).grantChannelAccess(CHANNEL, OWNER);
        }

        @Test
        @DisplayName("has the claim written down, so nobody else can take it")
        void recordsTheClaim() {
            service.verify(OWNER, "player", REF);

            verify(verifications).save(any(DiscordVerificationEntity.class));
        }

        @Test
        @DisplayName("can run it again and get back in")
        void reVerifyingIsAllowed() {
            when(verifications.findByOrderId(42L)).thenReturn(Optional.of(
                    new DiscordVerificationEntity(42L, OWNER, "player", CHANNEL)));

            DiscordVerificationService.Result result = service.verify(OWNER, "player", REF);

            assertThat(result.granted()).isTrue();
            verify(bot).grantChannelAccess(CHANNEL, OWNER);
        }

        @Test
        @DisplayName("is not fussy about how the reference was pasted")
        void normalisesTheReference() {
            assertThat(service.verify(OWNER, "player", "  `" + REF.toLowerCase() + "` ")
                    .granted()).isTrue();
        }
    }

    @Nested
    @DisplayName("somebody else's order reference")
    class Refusals {

        @Test
        @DisplayName("is refused once the order belongs to another account")
        void strangerIsRefused() {
            when(verifications.findByOrderId(42L)).thenReturn(Optional.of(
                    new DiscordVerificationEntity(42L, OWNER, "player", CHANNEL)));

            DiscordVerificationService.Result result = service.verify(STRANGER, "chancer", REF);

            assertThat(result.granted()).isFalse();
            assertThat(result.outcome()).isEqualTo(VerificationOutcome.ALREADY_CLAIMED);
            verify(bot, never()).grantChannelAccess(anyString(), eq(STRANGER));
        }

        @Test
        @DisplayName("tells a guesser nothing about whether the order is real")
        void aMissRevealsNothing() {
            when(orders.findByPublicRef("GFS-26-NOTREAL")).thenReturn(Optional.empty());

            String miss = service.verify(STRANGER, "chancer", "GFS-26-NOTREAL").message();

            // Not identical to ALREADY_CLAIMED — that distinction is unavoidable, since a
            // real owner needs to be told to contact support. What matters is that a miss
            // names no order and offers no detail to work from.
            assertThat(miss).doesNotContain("GFS-26-NOTREAL");
            assertThat(miss).contains("support");
        }

        @Test
        @DisplayName("stops being answered after five tries in a minute")
        void guessingIsRateLimited() {
            when(orders.findByPublicRef(anyString())).thenReturn(Optional.empty());

            for (int i = 0; i < 5; i++) {
                assertThat(service.verify(STRANGER, "chancer", "GFS-26-GUESS" + i).outcome())
                        .isEqualTo(VerificationOutcome.NOT_FOUND);
            }

            assertThat(service.verify(STRANGER, "chancer", "GFS-26-GUESS9").outcome())
                    .isEqualTo(VerificationOutcome.RATE_LIMITED);
        }

        @Test
        @DisplayName("does not spend one person's budget on another's")
        void limitsArePerAccount() {
            when(orders.findByPublicRef(anyString())).thenReturn(Optional.empty());
            for (int i = 0; i < 6; i++) {
                service.verify(STRANGER, "chancer", "GFS-26-GUESS" + i);
            }
            // Put the real order back: the blanket stub above covers every reference,
            // this one included, and the point of the test is the owner's budget.
            when(orders.findByPublicRef(REF)).thenReturn(Optional.of(order));

            assertThat(service.verify(OWNER, "player", REF).granted()).isTrue();
        }

        @Test
        @DisplayName("every attempt is written down, refused ones included")
        void attemptsAreLogged() {
            when(orders.findByPublicRef("GFS-26-NOTREAL")).thenReturn(Optional.empty());

            service.verify(STRANGER, "chancer", "GFS-26-NOTREAL");

            verify(attempts).save(any(DiscordAttemptEntity.class));
        }
    }

    @Nested
    @DisplayName("when the grant cannot happen")
    class Failures {

        @Test
        @DisplayName("an order with no ticket yet says so, rather than failing vaguely")
        void noTicketYet() {
            when(bot.findTicketChannel(REF)).thenReturn(Optional.empty());

            DiscordVerificationService.Result result = service.verify(OWNER, "player", REF);

            assertThat(result.outcome()).isEqualTo(VerificationOutcome.NO_TICKET);
            assertThat(result.message()).contains("payment is still being checked");
        }

        @Test
        @DisplayName("a failed grant leaves the order claimable rather than locked")
        void aFailedGrantClaimsNothing() {
            doThrow(new DiscordBotClient.DiscordException("boom"))
                    .when(bot).grantChannelAccess(CHANNEL, OWNER);

            DiscordVerificationService.Result result = service.verify(OWNER, "player", REF);

            assertThat(result.outcome()).isEqualTo(VerificationOutcome.ERROR);
            // The claim must not be written: the customer never got in, and a row here
            // would lock them out of retrying under their own account.
            verify(verifications, never()).save(any());
        }

        @Test
        @DisplayName("an unconfigured bot is an error, not a silent success")
        void botOff() {
            when(bot.isEnabled()).thenReturn(false);

            assertThat(service.verify(OWNER, "player", REF).outcome())
                    .isEqualTo(VerificationOutcome.ERROR);
        }
    }

    @Nested
    @DisplayName("customers who signed in with Discord")
    class DiscordAuthenticated {

        private AccountEntity discordAccount() {
            AccountEntity a = new AccountEntity("acc_1", "p@example.com", "hash",
                    AccountRole.CUSTOMER);
            a.setOauthProvider("discord");
            a.setOauthSubject(OWNER);
            return a;
        }

        @Test
        @DisplayName("are let in as the ticket opens, without verifying anything")
        void grantedAtTicketCreation() {
            when(order.getAccountId()).thenReturn(7L);
            when(accounts.findById(7L)).thenReturn(Optional.of(discordAccount()));

            service.grantAtTicketCreation(REF, CHANNEL);

            verify(bot).grantChannelAccess(CHANNEL, OWNER);
            verify(verifications).save(any(DiscordVerificationEntity.class));
        }

        @Test
        @DisplayName("are recognised so the storefront can show a channel link, not an invite")
        void recognisedForTheStorefront() {
            when(accounts.findById(7L)).thenReturn(Optional.of(discordAccount()));

            assertThat(service.isDiscordAuthenticated(7L)).isTrue();
        }

        @Test
        @DisplayName("a Google customer is not, and keeps the verification path")
        void googleCustomerIsNot() {
            AccountEntity google = new AccountEntity("acc_2", "g@example.com", "hash",
                    AccountRole.CUSTOMER);
            google.setOauthProvider("google");
            google.setOauthSubject("google-subject");
            when(order.getAccountId()).thenReturn(8L);
            when(accounts.findById(8L)).thenReturn(Optional.of(google));

            service.grantAtTicketCreation(REF, CHANNEL);

            assertThat(service.isDiscordAuthenticated(8L)).isFalse();
            verify(bot, never()).grantChannelAccess(anyString(), anyString());
        }

        @Test
        @DisplayName("a guest checkout has no account and is left alone")
        void guestCheckout() {
            when(order.getAccountId()).thenReturn(null);

            service.grantAtTicketCreation(REF, CHANNEL);

            verify(bot, never()).grantChannelAccess(anyString(), anyString());
        }

        @Test
        @DisplayName("a failed pre-grant does not stop the ticket opening")
        void preGrantFailureIsSwallowed() {
            when(order.getAccountId()).thenReturn(7L);
            when(accounts.findById(7L)).thenReturn(Optional.of(discordAccount()));
            doThrow(new DiscordBotClient.DiscordException("boom"))
                    .when(bot).grantChannelAccess(CHANNEL, OWNER);

            service.grantAtTicketCreation(REF, CHANNEL);
            // No exception escapes: the caller is mid-way through opening a ticket.
        }
    }
}
