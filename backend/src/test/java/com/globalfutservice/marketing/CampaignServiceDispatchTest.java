package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the background job's send does, with the committed steps mocked.
 */
class CampaignServiceDispatchTest {

    /** 01:30 on 26 Sep in India: "today" for an offer is the 26th. */
    private static final Instant NOW = Instant.parse("2026-09-25T20:00:00Z");
    private static final LocalDate TODAY_IN_INDIA = LocalDate.of(2026, 9, 26);
    private static final Long ID = 7L;

    private CampaignRepository campaigns;
    private CampaignRecipientRepository recipients;
    private CampaignSender sender;
    private CampaignService service;
    private CampaignEntity campaign;

    @BeforeEach
    void setUp() {
        campaigns = mock(CampaignRepository.class);
        recipients = mock(CampaignRecipientRepository.class);
        sender = mock(CampaignSender.class);
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.notifications().emailEnabled()).thenReturn(true);
        service = new CampaignService(campaigns, recipients, mock(MarketingAudienceRepository.class),
                mock(AccountRepository.class), sender, mock(CampaignRenderer.class),
                Clock.fixed(NOW, ZoneOffset.UTC), props);
        campaign = new CampaignEntity("n", "Subject", "Heading", "Body",
                CampaignAudience.ALL_OPTED_IN, 1L);
        when(campaigns.findById(ID)).thenReturn(Optional.of(campaign));
    }

    private static CampaignRecipientEntity row() {
        return new CampaignRecipientEntity(ID, 12L, "a@example.test");
    }

    @Nested
    class OfferEnded {

        @Test
        @DisplayName("a campaign whose offer ended is withdrawn, never claimed or sent")
        void withdrawn() {
            when(sender.withdrawIfOfferEnded(ID, TODAY_IN_INDIA)).thenReturn(true);

            assertThat(service.dispatch(ID)).isZero();

            verify(sender, never()).claim(any());
            verify(sender, never()).sendOne(any(), any());
        }

        @Test
        @DisplayName("the offer's end is judged in India's calendar")
        void indianToday() {
            service.dispatch(ID);

            verify(sender).withdrawIfOfferEnded(eq(ID), eq(TODAY_IN_INDIA));
        }

        @Test
        @DisplayName("a campaign whose offer is still running goes on to be claimed")
        void stillRunning() {
            when(sender.withdrawIfOfferEnded(ID, TODAY_IN_INDIA)).thenReturn(false);
            when(sender.claim(ID)).thenReturn(true);
            when(recipients.findByCampaignIdAndStatus(eq(ID), eq("PENDING"), any())).thenReturn(List.of());

            service.dispatch(ID);

            verify(sender).claim(ID);
            verify(sender).buildRecipients(eq(ID), any());
            verify(sender, never()).sendOne(anyLong(), anyLong());
        }
    }

    @Nested
    class Heartbeat {

        @Test
        @DisplayName("says the send is alive before every message, not once per batch")
        void beforeEachMessage() {
            when(sender.claim(ID)).thenReturn(true);
            when(recipients.findByCampaignIdAndStatus(eq(ID), eq("PENDING"), any()))
                    .thenReturn(List.of(row(), row()), List.of());

            service.dispatch(ID);

            InOrder order = inOrder(sender);
            order.verify(sender).heartbeat(ID);
            order.verify(sender).sendOne(eq(ID), any());
            order.verify(sender).heartbeat(ID);
            order.verify(sender).sendOne(eq(ID), any());
        }
    }

    @Nested
    class Resume {

        @Test
        @DisplayName("takes over only a send silent for ten minutes, and sends what is PENDING")
        void resumes() {
            when(sender.reclaimStalled(ID, NOW, NOW.minusSeconds(600))).thenReturn(true);
            when(recipients.findByCampaignIdAndStatus(eq(ID), eq("PENDING"), any()))
                    .thenReturn(List.of(row()), List.of());
            when(sender.sendOne(eq(ID), any())).thenReturn(true);
            when(recipients.countByCampaignIdAndStatus(ID, "SENT")).thenReturn(1L);

            int sent = service.resumeStalled(ID);

            assertThat(sent).isEqualTo(1);
            // The list is kept, not rebuilt; buildRecipients returns early when rows exist.
            verify(sender).buildRecipients(eq(ID), any());
            verify(sender).complete(ID, CampaignStatus.SENT);
            verify(sender, never()).claim(any());
        }

        @Test
        @DisplayName("leaves alone a send that is still alive, or that another instance took")
        void notOurs() {
            when(sender.reclaimStalled(eq(ID), any(), any())).thenReturn(false);

            assertThat(service.resumeStalled(ID)).isZero();

            verify(sender, never()).buildRecipients(any(), any());
            verify(sender, never()).sendOne(any(), any());
            verify(sender, never()).complete(any(), any());
        }

        @Test
        @DisplayName("does not resume once the offer has ended; the campaign is FAILED instead")
        void offerEndedMeanwhile() {
            when(sender.reclaimStalled(eq(ID), any(), any())).thenReturn(true);
            campaign.setOfferValidUntil(TODAY_IN_INDIA.minusDays(1));

            assertThat(service.resumeStalled(ID)).isZero();

            verify(sender).complete(ID, CampaignStatus.FAILED);
            verify(sender, never()).sendOne(any(), any());
        }

        @Test
        @DisplayName("resumes on the offer's last day")
        void lastDay() {
            when(sender.reclaimStalled(eq(ID), any(), any())).thenReturn(true);
            campaign.setOfferValidUntil(TODAY_IN_INDIA);
            when(recipients.findByCampaignIdAndStatus(eq(ID), eq("PENDING"), any())).thenReturn(List.of());

            service.resumeStalled(ID);

            verify(sender, never()).complete(ID, CampaignStatus.FAILED);
            verify(sender, times(1)).complete(eq(ID), any());
        }
    }

    @Nested
    class Outcome {

        @BeforeEach
        void claimed() {
            when(sender.claim(ID)).thenReturn(true);
            when(recipients.findByCampaignIdAndStatus(eq(ID), eq("PENDING"), any())).thenReturn(List.of());
        }

        private void rows(long sent, long failed) {
            when(recipients.countByCampaignIdAndStatus(ID, "SENT")).thenReturn(sent);
            when(recipients.countByCampaignIdAndStatus(ID, "FAILED")).thenReturn(failed);
        }

        @Test
        @DisplayName("a campaign that reached nobody because every message was refused is FAILED")
        void reachedNobody() {
            rows(0, 2);

            service.dispatch(ID);

            verify(sender).complete(ID, CampaignStatus.FAILED);
        }

        @Test
        @DisplayName("a campaign that reached some people is SENT, failures and all")
        void reachedSome() {
            rows(1, 1);

            service.dispatch(ID);

            verify(sender).complete(ID, CampaignStatus.SENT);
        }

        @Test
        @DisplayName("a retry that fails again does not undo an earlier run's successes")
        void earlierSuccessCounts() {
            // Nothing is accepted in this run, but three were in the first.
            rows(3, 2);

            service.dispatch(ID);

            verify(sender).complete(ID, CampaignStatus.SENT);
        }

        @Test
        @DisplayName("a campaign whose recipients all opted out before sending is SENT, not FAILED")
        void allSkipped() {
            rows(0, 0);

            service.dispatch(ID);

            verify(sender).complete(ID, CampaignStatus.SENT);
        }
    }
}
