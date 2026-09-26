package com.globalfutservice.marketing;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The campaign builder's switches, as a recipient experiences them.
 */
class CampaignRendererSwitchesTest {

    private static final String SITE = "https://globalfutservices.com";
    private static final UUID RECIPIENT = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final String UNSUB = SITE + "/unsubscribe?t=x";

    private CampaignRenderer renderer;

    @BeforeEach
    void setUp() {
        AppProperties props = mock(AppProperties.class);
        when(props.publicUrl()).thenReturn(SITE);
        when(props.instagramUrl()).thenReturn("https://www.instagram.com/global_fut_services/");
        when(props.discordInvite()).thenReturn("https://discord.com/invite/8FeP7C6tXt");
        renderer = new CampaignRenderer(props);
    }

    private static CampaignEntity sale() {
        CampaignEntity c = new CampaignEntity("n", "Sale", "Coins sale", "Body",
                CampaignAudience.ALL_OPTED_IN, 1L);
        c.setType(CampaignType.COINS);
        c.setCtaText(CampaignType.BUTTON_TEXT);
        c.setCtaPath(CampaignType.COINS.buttonPath());
        c.setPromoCode("HUNTER10");
        return c;
    }

    @Test
    @DisplayName("tracking on: the recipient's pixel is there and the button counts the click")
    void trackingOn() {
        String html = renderer.forRecipient(sale(), UNSUB, RECIPIENT).html();

        assertThat(html)
                .contains(SITE + "/api/v1/marketing/o/" + RECIPIENT + ".gif")
                .contains(SITE + "/api/v1/marketing/c/" + RECIPIENT);
    }

    @Test
    @DisplayName("tracking off: no pixel, and the button links straight to the page")
    void trackingOff() {
        CampaignEntity c = sale();
        c.setTrackingEnabled(false);

        String html = renderer.forRecipient(c, UNSUB, RECIPIENT).html();

        assertThat(html)
                .doesNotContain("/api/v1/marketing/o/")
                .doesNotContain("/api/v1/marketing/c/")
                .doesNotContain(RECIPIENT.toString())
                .contains("href=\"" + SITE + "/order?service=TRADING_SERVICE\"");
    }

    @Test
    @DisplayName("tracking off still carries the unsubscribe link")
    void trackingOffStillUnsubscribes() {
        CampaignEntity c = sale();
        c.setTrackingEnabled(false);

        assertThat(renderer.forRecipient(c, UNSUB, RECIPIENT).html()).contains("/unsubscribe?t=x");
    }

    @Test
    @DisplayName("a hidden code stays on the campaign but out of the email")
    void hiddenCode() {
        CampaignEntity c = sale();
        c.setShowPromoCode(false);

        var out = renderer.forRecipient(c, UNSUB, RECIPIENT);

        assertThat(out.html()).doesNotContain("HUNTER10").doesNotContain("USE CODE");
        assertThat(out.text()).doesNotContain("HUNTER10");
        assertThat(c.getPromoCode()).isEqualTo("HUNTER10");
    }

    @Test
    @DisplayName("the banner address changes when the campaign does, so a replaced banner is not served stale")
    void bannerIsVersioned() {
        CampaignEntity c = sale();
        c.setBanner("image/png", new byte[]{1, 2, 3});

        assertThat(renderer.bannerUrl(c))
                .startsWith(SITE + "/api/v1/marketing/campaigns/" + c.getPublicId() + "/banner?v=")
                .endsWith(String.valueOf(c.getUpdatedAt().toEpochMilli()));
    }
}
