package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

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
        CampaignEntity c = new CampaignEntity("n", "Subject", "Heading", "Body",
                CampaignAudience.ALL_OPTED_IN, 1L);
        when(campaigns.findById(ID)).thenReturn(Optional.of(c));
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
}
