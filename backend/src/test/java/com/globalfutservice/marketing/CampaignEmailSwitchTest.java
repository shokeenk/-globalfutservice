package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * GFS_EMAIL_ENABLED is the switch for campaigns too: off, nothing is sent, scheduled or
 * claimed, and nothing is recorded against a recipient.
 */
class CampaignEmailSwitchTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private CampaignRepository campaigns;
    private CampaignSender sender;
    private AppProperties props;
    private CampaignService service;
    private CampaignEntity draft;

    @BeforeEach
    void setUp() {
        campaigns = mock(CampaignRepository.class);
        sender = mock(CampaignSender.class);
        props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.notifications().emailEnabled()).thenReturn(false);
        service = new CampaignService(campaigns, mock(CampaignRecipientRepository.class),
                mock(MarketingAudienceRepository.class), mock(AccountRepository.class), sender,
                mock(CampaignRenderer.class), Clock.fixed(NOW, ZoneOffset.UTC), props);
        draft = new CampaignEntity("n", "Subject", "Heading", "Body",
                CampaignAudience.ALL_OPTED_IN, 1L);
        when(campaigns.findByPublicId(draft.getPublicId())).thenReturn(Optional.of(draft));
    }

    @Test
    @DisplayName("scheduling is refused, and the campaign stays a draft")
    void scheduleRefused() {
        assertThatThrownBy(() -> service.schedule(draft.getPublicId(), NOW.plusSeconds(3600)))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("Email is switched off");
        assertThat(draft.getStatus()).isEqualTo(CampaignStatus.DRAFT);
    }

    @Test
    @DisplayName("send now is refused before anything is claimed")
    void sendNowRefused() {
        assertThatThrownBy(() -> service.sendNow(draft.getPublicId()))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("Email is switched off");
        verify(sender, never()).claim(any());
    }

    @Test
    @DisplayName("a test copy is refused too")
    void testCopyRefused() {
        assertThatThrownBy(() -> service.sendTest(draft.getPublicId(), 9L))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("Email is switched off");
        verify(sender, never()).sendTest(anyString(), any());
    }

    @Test
    @DisplayName("the job leaves due campaigns scheduled rather than claiming them")
    void jobHoldsBack() {
        CampaignService jobService = mock(CampaignService.class);
        when(campaigns.findDue(any())).thenReturn(List.of(draft));

        new CampaignScheduleJob(campaigns, jobService, props).sendDueCampaigns();

        verifyNoInteractions(jobService);
    }

    @Test
    @DisplayName("the job sends them once email is on")
    void jobSendsWhenOn() {
        CampaignService jobService = mock(CampaignService.class);
        when(campaigns.findDue(any())).thenReturn(List.of(draft));
        when(props.notifications().emailEnabled()).thenReturn(true);

        new CampaignScheduleJob(campaigns, jobService, props).sendDueCampaigns();

        verify(jobService).dispatch(draft.getId());
    }

    @Test
    @DisplayName("an empty tick dispatches nothing")
    void emptyTick() {
        CampaignService jobService = mock(CampaignService.class);
        when(campaigns.findDue(any())).thenReturn(List.of());

        new CampaignScheduleJob(campaigns, jobService, props).sendDueCampaigns();

        verify(jobService, never()).dispatch(any());
    }
}
