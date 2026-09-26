package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.notify.email.TransactionalEmails;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The builder's later steps, on the server: the hero lines, the audience, the sending
 * allowance, and the test copy.
 */
class CampaignServiceStepsTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private CampaignRepository campaigns;
    private CampaignRecipientRepository recipients;
    private AccountRepository accounts;
    private CampaignSender sender;
    private CampaignRenderer renderer;
    private AppProperties props;
    private CampaignService service;

    @BeforeEach
    void setUp() {
        campaigns = mock(CampaignRepository.class);
        recipients = mock(CampaignRecipientRepository.class);
        accounts = mock(AccountRepository.class);
        sender = mock(CampaignSender.class);
        renderer = mock(CampaignRenderer.class);
        props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        service = new CampaignService(campaigns, recipients, mock(MarketingAudienceRepository.class),
                accounts, sender, renderer, Clock.fixed(NOW, ZoneOffset.UTC), props);
    }

    private CampaignEntity campaignIn(CampaignStatus status) {
        CampaignEntity c = new CampaignEntity("n", "Subject", "Coins sale", "b",
                CampaignAudience.ALL_OPTED_IN, 1L);
        c.setStatus(status);
        when(campaigns.findByPublicId(c.getPublicId())).thenReturn(Optional.of(c));
        return c;
    }

    @Nested
    class Content {

        @Test
        @DisplayName("sets the lines above and below the headline")
        void sets() {
            CampaignEntity c = campaignIn(CampaignStatus.DRAFT);

            service.replaceContent(c.getPublicId(), "Team of the Year", "Build your dream squad");

            assertThat(c.getHeroKicker()).isEqualTo("Team of the Year");
            assertThat(c.getHeroSubline()).isEqualTo("Build your dream squad");
        }

        @Test
        @DisplayName("an emptied line is removed, not stored as blank")
        void clears() {
            CampaignEntity c = campaignIn(CampaignStatus.DRAFT);
            c.setHeroKicker("old");

            service.replaceContent(c.getPublicId(), "  ", null);

            assertThat(c.getHeroKicker()).isNull();
            assertThat(c.getHeroSubline()).isNull();
        }

        @Test
        @DisplayName("a campaign already sent cannot be edited")
        void sentRefused() {
            CampaignEntity c = campaignIn(CampaignStatus.SENT);

            assertThatThrownBy(() -> service.replaceContent(c.getPublicId(), "x", "y"))
                    .isInstanceOf(ApiExceptions.BadRequestException.class);
        }
    }

    @Nested
    class Audience {

        @Test
        @DisplayName("chooses one of the fixed segments")
        void chooses() {
            CampaignEntity c = campaignIn(CampaignStatus.DRAFT);

            service.chooseAudience(c.getPublicId(), CampaignAudience.COACHING_BUYERS);

            assertThat(c.getAudience()).isEqualTo(CampaignAudience.COACHING_BUYERS);
        }

        @Test
        @DisplayName("only a draft's audience can change: a scheduled one is withdrawn first")
        void draftsOnly() {
            // The existing rule, and the right one: a campaign must not change underneath
            // its own schedule. Withdrawing it returns it to a state that can be edited.
            for (CampaignStatus locked : new CampaignStatus[]{
                    CampaignStatus.SCHEDULED, CampaignStatus.SENDING, CampaignStatus.SENT}) {
                CampaignEntity c = campaignIn(locked);
                assertThatThrownBy(() -> service.chooseAudience(c.getPublicId(), CampaignAudience.COINS_BUYERS))
                        .isInstanceOf(ApiExceptions.BadRequestException.class);
                assertThat(c.getAudience()).isEqualTo(CampaignAudience.ALL_OPTED_IN);
            }
        }
    }

    @Nested
    class Quota {

        @Test
        @DisplayName("what is left of the cap after the last 24 hours of campaign sends")
        void remaining() {
            when(props.campaigns().dailyCap()).thenReturn(100);
            when(recipients.countByStatusAndSentAtAfter(eq("SENT"), any())).thenReturn(37L);

            CampaignService.SendQuota q = service.quota();

            assertThat(q.dailyCap()).isEqualTo(100);
            assertThat(q.sentLast24h()).isEqualTo(37);
            assertThat(q.remaining()).isEqualTo(63);
        }

        @Test
        @DisplayName("counts a rolling 24 hours, not since midnight in any one zone")
        void rollingWindow() {
            when(props.campaigns().dailyCap()).thenReturn(100);
            ArgumentCaptor<Instant> since = ArgumentCaptor.forClass(Instant.class);
            when(recipients.countByStatusAndSentAtAfter(eq("SENT"), since.capture())).thenReturn(0L);

            service.quota();

            assertThat(since.getValue()).isEqualTo(Instant.parse("2026-09-25T12:00:00Z"));
        }

        @Test
        @DisplayName("never reports less than nothing left")
        void neverNegative() {
            when(props.campaigns().dailyCap()).thenReturn(100);
            when(recipients.countByStatusAndSentAtAfter(eq("SENT"), any())).thenReturn(140L);

            assertThat(service.quota().remaining()).isZero();
        }
    }

    @Nested
    class TestCopy {

        private final TransactionalEmails.Rendered preview =
                new TransactionalEmails.Rendered("Subject", "<html>preview</html>", "text");

        @Test
        @DisplayName("goes to the signed-in admin's own address, as the inert preview")
        void goesToTheAdmin() {
            CampaignEntity c = campaignIn(CampaignStatus.DRAFT);
            AccountEntity admin = mock(AccountEntity.class);
            when(admin.getEmail()).thenReturn("admin@example.test");
            when(accounts.findById(9L)).thenReturn(Optional.of(admin));
            when(renderer.preview(c)).thenReturn(preview);

            String to = service.sendTest(c.getPublicId(), 9L);

            assertThat(to).isEqualTo("admin@example.test");
            // The preview, not a recipient copy: no pixel, no click counter, no real
            // unsubscribe token, so a test cannot skew numbers or unsubscribe the tester.
            verify(sender).sendTest("admin@example.test", preview);
            verify(renderer, never()).forRecipient(any(), anyString(), any());
            verify(recipients, never()).save(any());
        }

        @Test
        @DisplayName("says why when the relay refuses, rather than failing generically")
        void relayReason() {
            CampaignEntity c = campaignIn(CampaignStatus.DRAFT);
            AccountEntity admin = mock(AccountEntity.class);
            when(admin.getEmail()).thenReturn("admin@example.test");
            when(accounts.findById(9L)).thenReturn(Optional.of(admin));
            when(renderer.preview(c)).thenReturn(preview);
            doThrow(new IllegalStateException("535 Authentication failed"))
                    .when(sender).sendTest(anyString(), any());

            assertThatThrownBy(() -> service.sendTest(c.getPublicId(), 9L))
                    .isInstanceOf(ApiExceptions.BadRequestException.class)
                    .hasMessageContaining("535 Authentication failed");
        }

        @Test
        @DisplayName("refuses an account with no address rather than sending somewhere else")
        void noAddress() {
            CampaignEntity c = campaignIn(CampaignStatus.DRAFT);
            when(accounts.findById(9L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.sendTest(c.getPublicId(), 9L))
                    .isInstanceOf(ApiExceptions.BadRequestException.class);
            verify(sender, never()).sendTest(anyString(), any());
        }
    }
}
