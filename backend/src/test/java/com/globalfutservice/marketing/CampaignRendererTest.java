package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.email.TransactionalEmails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Where the links in a campaign point.
 *
 * <p>Worth its own test because every one of them is assembled from configuration and
 * none of them can be checked after the fact: a campaign button that goes to the wrong
 * place, or that skips the click counter, is only discovered once the mail is already in
 * several hundred inboxes and cannot be recalled.
 */
class CampaignRendererTest {

    private static final String SITE = "https://globalfutservices.com";
    private static final UUID RECIPIENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ACCOUNT = UUID.fromString("99999999-8888-7777-6666-555555555555");

    private CampaignRenderer renderer;

    @BeforeEach
    void setUp() {
        AppProperties props = mock(AppProperties.class);
        when(props.publicUrl()).thenReturn(SITE);
        when(props.discordInvite()).thenReturn("https://discord.com/invite/8FeP7C6tXt");
        when(props.instagramUrl()).thenReturn("https://www.instagram.com/global_fut_services/");
        renderer = new CampaignRenderer(props);
    }

    private static CampaignEntity sale() {
        CampaignEntity c = new CampaignEntity(
                "Friday Coin Sale", "Friday Coin Sale — 10% off", "FRIDAY COIN SALE",
                "Every coin order is 10% off for 48 hours.",
                CampaignAudience.ALL_OPTED_IN, 1L);
        c.setCtaText("BUY COINS");
        c.setCtaPath("/order?service=TRADING_SERVICE");
        return c;
    }

    @Nested
    @DisplayName("the call-to-action button")
    class Cta {

        @Test
        @DisplayName("goes through the click counter for a real recipient")
        void tracksRealRecipients() {
            TransactionalEmails.Rendered out =
                    renderer.render(sale(), SITE + "/unsubscribe?t=x", null, RECIPIENT);

            assertThat(out.html())
                    .contains(SITE + "/api/v1/marketing/c/" + RECIPIENT);
        }

        @Test
        @DisplayName("does not link straight past the counter, or clicks read zero forever")
        void doesNotBypassTheCounter() {
            TransactionalEmails.Rendered out =
                    renderer.render(sale(), SITE + "/unsubscribe?t=x", null, RECIPIENT);

            assertThat(out.html()).doesNotContain("href=\"" + SITE + "/order?service=");
        }

        @Test
        @DisplayName("links direct in a preview, which has no recipient to count")
        void previewLinksDirect() {
            TransactionalEmails.Rendered out = renderer.preview(sale());

            assertThat(out.html()).contains(SITE + "/order?service=TRADING_SERVICE");
            assertThat(out.html()).doesNotContain("/api/v1/marketing/c/");
        }

        @Test
        @DisplayName("the counter redirects to the destination the campaign actually names")
        void counterResolvesToTheRealDestination() {
            assertThat(renderer.ctaUrlFor(sale()))
                    .isEqualTo(SITE + "/order?service=TRADING_SERVICE");
        }

        @Test
        @DisplayName("a campaign with no button produces no button")
        void noCtaNoButton() {
            CampaignEntity plain = sale();
            plain.setCtaText(null);
            plain.setCtaPath(null);

            TransactionalEmails.Rendered out =
                    renderer.render(plain, SITE + "/unsubscribe?t=x", null, RECIPIENT);

            assertThat(out.html()).doesNotContain("/api/v1/marketing/c/");
        }
    }

    @Nested
    @DisplayName("tracking and consent links")
    class Links {

        @Test
        @DisplayName("a preview never carries a tracking pixel")
        void previewIsNotTracked() {
            assertThat(renderer.preview(sale()).html())
                    .doesNotContain("/api/v1/marketing/o/");
        }

        @Test
        @DisplayName("the unsubscribe link carries the account token, not the address")
        void unsubscribeCarriesAToken() {
            String url = renderer.unsubscribeUrl(ACCOUNT, "camp_abc");

            assertThat(url).isEqualTo(
                    SITE + "/unsubscribe?t=" + ACCOUNT + "&c=camp_abc");
        }

        @Test
        @DisplayName("the open pixel is addressed to the recipient row, not the campaign")
        void pixelIsPerRecipient() {
            assertThat(renderer.pixelUrl(RECIPIENT))
                    .isEqualTo(SITE + "/api/v1/marketing/o/" + RECIPIENT + ".gif");
        }

        @Test
        @DisplayName("a campaign with no banner advertises no banner URL")
        void noBannerNoUrl() {
            assertThat(renderer.bannerUrl(sale())).isNull();
        }
    }
}
