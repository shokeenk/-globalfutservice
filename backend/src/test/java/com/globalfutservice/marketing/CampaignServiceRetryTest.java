package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Retrying the people a finished campaign failed to reach: requeue, then leave the
 * sending to the job.
 */
class CampaignServiceRetryTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

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
    }

    private CampaignEntity campaignIn(CampaignStatus status) {
        CampaignEntity c = new CampaignEntity("n", "Subject", "Heading", "Body",
                CampaignAudience.ALL_OPTED_IN, 1L);
        c.setStatus(status);
        when(campaigns.findByPublicId(c.getPublicId())).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    @DisplayName("requeues the failed rows and makes the campaign due now, sending nothing itself")
    void requeuesAndQueues() {
        CampaignEntity c = campaignIn(CampaignStatus.SENT);
        when(recipients.requeueFailed(c.getId())).thenReturn(2);
        when(recipients.countByCampaignId(c.getId())).thenReturn(5L);
        when(recipients.countByCampaignIdAndStatus(c.getId(), "PENDING")).thenReturn(2L);

        CampaignEntity queued = service.retryFailed(c.getPublicId());

        verify(recipients).requeueFailed(c.getId());
        assertThat(queued.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(queued.getScheduledAt()).isEqualTo(NOW);
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("resumes a send that broke part-way, whose rest is still PENDING")
    void resumesABrokenSend() {
        CampaignEntity c = campaignIn(CampaignStatus.FAILED);
        when(recipients.requeueFailed(c.getId())).thenReturn(0);
        when(recipients.countByCampaignId(c.getId())).thenReturn(40L);
        when(recipients.countByCampaignIdAndStatus(c.getId(), "PENDING")).thenReturn(37L);

        service.retryFailed(c.getPublicId());

        assertThat(c.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
    }

    @Test
    @DisplayName("resumes a send that broke before its list was written")
    void resumesBeforeTheList() {
        CampaignEntity c = campaignIn(CampaignStatus.FAILED);
        when(recipients.countByCampaignId(c.getId())).thenReturn(0L);

        service.retryFailed(c.getPublicId());

        assertThat(c.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
    }

    @Test
    @DisplayName("says so when there is nobody left to retry")
    void nobodyLeft() {
        CampaignEntity c = campaignIn(CampaignStatus.SENT);
        when(recipients.countByCampaignId(c.getId())).thenReturn(5L);
        when(recipients.countByCampaignIdAndStatus(c.getId(), "PENDING")).thenReturn(0L);

        assertThatThrownBy(() -> service.retryFailed(c.getPublicId()))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("nobody to retry");
        assertThat(c.getStatus()).isEqualTo(CampaignStatus.SENT);
    }

    @Test
    @DisplayName("refuses once the offer has ended, before touching a row")
    void offerEnded() {
        CampaignEntity c = campaignIn(CampaignStatus.SENT);
        c.setOfferValidUntil(LocalDate.of(2026, 9, 25));

        assertThatThrownBy(() -> service.retryFailed(c.getPublicId()))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("ended on 25 Sep 2026");
        verify(recipients, never()).requeueFailed(any());
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"DRAFT", "SCHEDULED", "SENDING", "CANCELLED"})
    @DisplayName("only a finished campaign can be retried; a send in flight is left alone")
    void unfinishedRefused(CampaignStatus status) {
        CampaignEntity c = campaignIn(status);

        assertThatThrownBy(() -> service.retryFailed(c.getPublicId()))
                .isInstanceOf(ApiExceptions.BadRequestException.class);
        verify(recipients, never()).requeueFailed(any());
        assertThat(c.getStatus()).isEqualTo(status);
    }
}
