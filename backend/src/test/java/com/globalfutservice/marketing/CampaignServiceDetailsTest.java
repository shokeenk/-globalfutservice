package com.globalfutservice.marketing;

import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rules the campaign builder's first step is held to on the server.
 */
class CampaignServiceDetailsTest {

    /**
     * 20:00 UTC on 25 Sep is 01:30 on 26 Sep in India. Chosen because it is the moment the
     * two calendars disagree: "today" must be the 26th here, or an offer ending on the
     * 25th would still be accepted for five and a half hours after it ended in India.
     */
    private static final Instant NOW = Instant.parse("2026-09-25T20:00:00Z");
    private static final LocalDate TODAY_IN_INDIA = LocalDate.of(2026, 9, 26);

    private CampaignRepository campaigns;
    private MarketingAudienceRepository audience;
    private CampaignSender sender;
    private CampaignService service;

    @BeforeEach
    void setUp() {
        campaigns = mock(CampaignRepository.class);
        audience = mock(MarketingAudienceRepository.class);
        sender = mock(CampaignSender.class);
        service = new CampaignService(campaigns, mock(CampaignRecipientRepository.class), audience,
                mock(AccountRepository.class), sender,
                mock(CampaignRenderer.class), Clock.fixed(NOW, ZoneOffset.UTC),
                mock(com.globalfutservice.config.AppProperties.class));
        when(campaigns.save(any(CampaignEntity.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static CampaignDetails details(CampaignType type, LocalDate until, boolean button,
                                           String offer, String code) {
        return new CampaignDetails("TOTY Special", "TOTY is here", type, "TOTY COINS SALE",
                offer, code, until, "Team of the Year is here.", button, true, true);
    }

    private CampaignEntity draftIn(CampaignStatus status, LocalDate until) {
        CampaignEntity c = new CampaignEntity("n", "s", "h", "b", CampaignAudience.ALL_OPTED_IN, 1L);
        c.setStatus(status);
        c.setOfferValidUntil(until);
        when(campaigns.findByPublicId(c.getPublicId())).thenReturn(Optional.of(c));
        return c;
    }

    @Nested
    class Saving {

        @Test
        @DisplayName("the promo title becomes the heading and the description the body")
        void mapsFields() {
            CampaignEntity c = service.createDraft(
                    details(CampaignType.COINS, null, true, "15% OFF", "HUNTER10"), 7L);

            assertThat(c.getHeading()).isEqualTo("TOTY COINS SALE");
            assertThat(c.getBody()).isEqualTo("Team of the Year is here.");
            assertThat(c.getType()).isEqualTo(CampaignType.COINS);
            assertThat(c.getOfferText()).isEqualTo("15% OFF");
            assertThat(c.getPromoCode()).isEqualTo("HUNTER10");
            assertThat(c.getCreatedBy()).isEqualTo(7L);
        }

        @Test
        @DisplayName("a new draft goes to everyone opted in; the builder picks the audience later")
        void startsOnConsentOnlyAudience() {
            CampaignEntity c = service.createDraft(details(CampaignType.COACHING, null, true, null, null), 1L);

            // Not COACHING_BUYERS: the type is what the campaign is about, not who gets it.
            assertThat(c.getAudience()).isEqualTo(CampaignAudience.ALL_OPTED_IN);
        }

        @ParameterizedTest
        @EnumSource(CampaignType.class)
        @DisplayName("the button's destination follows the type, and is always a fixed page")
        void buttonFollowsType(CampaignType type) {
            CampaignEntity c = service.createDraft(details(type, null, true, null, null), 1L);

            assertThat(c.getCtaText()).isEqualTo("ORDER NOW");
            assertThat(c.getCtaPath()).isEqualTo(type.buttonPath());
            assertThat(List.of(CtaPreset.values()).stream().map(CtaPreset::path))
                    .contains(c.getCtaPath());
        }

        @Test
        @DisplayName("switching the button off removes it entirely")
        void noButton() {
            CampaignEntity c = service.createDraft(details(CampaignType.COINS, null, false, null, null), 1L);

            assertThat(c.getCtaText()).isNull();
            assertThat(c.getCtaPath()).isNull();
        }

        @Test
        @DisplayName("blank optional fields are stored as absent, not as empty strings")
        void blanksAreNull() {
            CampaignEntity c = service.createDraft(details(CampaignType.COINS, null, true, "  ", ""), 1L);

            assertThat(c.getOfferText()).isNull();
            assertThat(c.getPromoCode()).isNull();
        }

        @Test
        @DisplayName("saving the step replaces it whole, so a field can be cleared")
        void replaceClears() {
            CampaignEntity c = draftIn(CampaignStatus.DRAFT, TODAY_IN_INDIA.plusDays(3));
            c.setOfferText("15% OFF");

            service.replaceDetails(c.getPublicId(), details(CampaignType.GENERAL, null, true, null, null));

            assertThat(c.getOfferText()).isNull();
            assertThat(c.getOfferValidUntil()).isNull();
            assertThat(c.getType()).isEqualTo(CampaignType.GENERAL);
        }

        @Test
        @DisplayName("a campaign that has gone out cannot have its details replaced")
        void sentIsNotEditable() {
            CampaignEntity c = draftIn(CampaignStatus.SENT, null);

            assertThatThrownBy(() -> service.replaceDetails(c.getPublicId(),
                    details(CampaignType.COINS, null, true, null, null)))
                    .isInstanceOf(ApiExceptions.BadRequestException.class);
        }
    }

    @Nested
    class OfferDates {

        @Test
        @DisplayName("today in India is accepted even while it is still yesterday in UTC")
        void todayIsIndianToday() {
            CampaignEntity c = service.createDraft(
                    details(CampaignType.COINS, TODAY_IN_INDIA, true, null, null), 1L);

            assertThat(c.getOfferValidUntil()).isEqualTo(TODAY_IN_INDIA);
        }

        @Test
        @DisplayName("a last day that has passed in India is refused, though it is today in UTC")
        void pastIsRefused() {
            LocalDate utcToday = LocalDate.of(2026, 9, 25);

            assertThatThrownBy(() -> service.createDraft(
                    details(CampaignType.COINS, utcToday, true, null, null), 1L))
                    .isInstanceOf(ApiExceptions.BadRequestException.class)
                    .hasMessageContaining("already passed");
        }

        @Test
        @DisplayName("an expired offer cannot be sent, and the send is refused before anything is claimed")
        void expiredCannotSend() {
            CampaignEntity c = draftIn(CampaignStatus.DRAFT, TODAY_IN_INDIA.minusDays(1));

            assertThatThrownBy(() -> service.sendNow(c.getPublicId()))
                    .isInstanceOf(ApiExceptions.BadRequestException.class)
                    .hasMessageContaining("ended on 25 Sep 2026");
            verify(sender, never()).claim(any());
        }

        @Test
        @DisplayName("a campaign cannot be scheduled to land after its offer ends")
        void cannotScheduleAfterEnd() {
            CampaignEntity c = draftIn(CampaignStatus.DRAFT, TODAY_IN_INDIA.plusDays(2));
            when(audience.optedIn()).thenReturn(List.of(mock(AccountEntity.class)));
            // 00:30 on the 29th in India: the day after the offer's last day.
            Instant late = Instant.parse("2026-09-28T19:00:00Z");

            assertThatThrownBy(() -> service.schedule(c.getPublicId(), late))
                    .isInstanceOf(ApiExceptions.BadRequestException.class)
                    .hasMessageContaining("after the offer ends on 28 Sep 2026");
        }

        @Test
        @DisplayName("scheduling on the offer's last day is allowed")
        void canScheduleOnLastDay() {
            CampaignEntity c = draftIn(CampaignStatus.DRAFT, TODAY_IN_INDIA.plusDays(2));
            when(audience.optedIn()).thenReturn(List.of(mock(AccountEntity.class)));
            // 23:00 on the 28th in India.
            Instant lastEvening = Instant.parse("2026-09-28T17:30:00Z");

            service.schedule(c.getPublicId(), lastEvening);

            assertThat(c.getStatus()).isEqualTo(CampaignStatus.SCHEDULED);
        }
    }
}
