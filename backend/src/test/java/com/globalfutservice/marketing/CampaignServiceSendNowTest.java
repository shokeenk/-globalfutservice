package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * "Send now" queues the campaign for the background job and returns; it never sends on
 * the request thread.
 */
class CampaignServiceSendNowTest {

    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    private CampaignRepository campaigns;
    private CampaignSender sender;
    private CampaignService service;

    @BeforeEach
    void setUp() {
        campaigns = mock(CampaignRepository.class);
        sender = mock(CampaignSender.class);
        MarketingAudienceRepository audience = mock(MarketingAudienceRepository.class);
        when(audience.optedIn()).thenReturn(List.of(mock(AccountEntity.class)));
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.notifications().emailEnabled()).thenReturn(true);
        service = new CampaignService(campaigns, mock(CampaignRecipientRepository.class), audience,
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
    @DisplayName("a draft becomes due now, and nothing is claimed or sent in the request")
    void queuesADraft() {
        CampaignEntity c = campaignIn(CampaignStatus.DRAFT);

        CampaignEntity queued = service.sendNow(c.getPublicId());

        assertThat(queued.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        assertThat(queued.getScheduledAt()).isEqualTo(NOW);
        verifyNoInteractions(sender);
    }

    @Test
    @DisplayName("a campaign scheduled for later is brought forward to now")
    void bringsForward() {
        CampaignEntity c = campaignIn(CampaignStatus.SCHEDULED);
        c.setScheduledAt(NOW.plusSeconds(86_400));

        service.sendNow(c.getPublicId());

        assertThat(c.getScheduledAt()).isEqualTo(NOW);
        verifyNoInteractions(sender);
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = {"SENDING", "SENT", "CANCELLED", "FAILED"})
    @DisplayName("anything past scheduling is refused and left as it was")
    void refusesTheRest(CampaignStatus status) {
        CampaignEntity c = campaignIn(status);

        assertThatThrownBy(() -> service.sendNow(c.getPublicId()))
                .isInstanceOf(ApiExceptions.BadRequestException.class)
                .hasMessageContaining("Only a draft or scheduled campaign");
        assertThat(c.getStatus()).isEqualTo(status);
        assertThat(c.getScheduledAt()).isNull();
    }
}
