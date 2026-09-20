package com.globalfutservice.notify.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The promotional renderer, and the guarantees it owes a recipient.
 *
 * <p>The assertions that matter most here are the ones about consent and escaping. A
 * campaign is written by a human into a form and then sent to every opted-in customer at
 * once, so this is the one renderer where the input is neither a constant nor a value the
 * application computed.
 */
class PromotionalEmailTest {

    private static final EmailTemplate.Brand BRAND = new EmailTemplate.Brand(
            "https://globalfutservices.com", "globalfutservices.com",
            "https://discord.com/invite/8FeP7C6tXt", "Join the GFS Discord",
            "https://www.instagram.com/global_fut_services/", "@global_fut_services");

    private static final String UNSUB =
            "https://globalfutservices.com/unsubscribe?t=abc-123&c=camp_1";
    private static final String PIXEL =
            "https://globalfutservices.com/api/v1/marketing/o/def-456.gif";

    private static PromotionalEmail.Campaign sale() {
        return new PromotionalEmail.Campaign(
                "Friday Coin Sale — 10% off",
                "FRIDAY COIN SALE",
                "The transfer market does not wait.\n\nEvery coin order is 10% off for 48 hours.",
                "FRIDAY10",
                "BUY COINS",
                "https://globalfutservices.com/order?service=TRADING_SERVICE",
                null);
    }

    private static PromotionalEmail.Delivery delivery() {
        return new PromotionalEmail.Delivery(UNSUB, PIXEL);
    }

    @Test
    @DisplayName("a campaign without an unsubscribe link will not render at all")
    void refuses_to_render_without_an_opt_out() {
        // The single failure in this system that cannot be undone after the fact: once
        // four hundred people have a promotional email with no way out, no later fix
        // reaches them. So it is refused here rather than defaulted to something.
        assertThatThrownBy(() -> PromotionalEmail.render(
                sale(), BRAND, new PromotionalEmail.Delivery(null, PIXEL)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsubscribe");

        assertThatThrownBy(() -> PromotionalEmail.render(
                sale(), BRAND, new PromotionalEmail.Delivery("  ", PIXEL)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("carries the opt-out in both the HTML and the plain-text part")
    void opt_out_in_both_parts() {
        var r = PromotionalEmail.render(sale(), BRAND, delivery());
        // The ampersand is escaped inside an href, as it must be — so the assertion is
        // on the escaped form rather than the raw URL.
        assertThat(r.html())
                .contains("/unsubscribe?t=abc-123&amp;c=camp_1")
                .containsIgnoringCase("unsubscribe");
        // A recipient reading the text/plain alternative must be able to leave too.
        assertThat(r.text()).contains(UNSUB).containsIgnoringCase("unsubscribe");
    }

    @Test
    @DisplayName("says it is marketing, and that order email is unaffected")
    void distinguishes_itself_from_transactional() {
        var r = PromotionalEmail.render(sale(), BRAND, delivery());
        assertThat(r.html())
                .contains("opted in")
                .contains("marketing")
                .containsIgnoringCase("order updates are sent separately")
                .doesNotContain("not marketing");   // that is the transactional wording
    }

    @Test
    @DisplayName("renders the admin's content: heading, body, promo code and button")
    void renders_the_campaign() {
        var r = PromotionalEmail.render(sale(), BRAND, delivery());
        assertThat(r.html())
                .contains("FRIDAY COIN SALE")
                .contains("The transfer market does not wait.")
                .contains("FRIDAY10")
                .contains("BUY COINS")
                .contains("/order?service=TRADING_SERVICE");
        assertThat(r.subject()).isEqualTo("Friday Coin Sale — 10% off");
    }

    @Test
    @DisplayName("blank lines in the admin's copy become paragraphs")
    void paragraphs_from_blank_lines() {
        var r = PromotionalEmail.render(sale(), BRAND, delivery());
        // Two paragraphs in, two <p> out — not one block with a literal newline in it.
        assertThat(r.html().split("<p style=").length - 1).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("admin-typed copy is escaped, not rendered as markup")
    void escapes_admin_input() {
        var hostile = new PromotionalEmail.Campaign(
                "Sale", "<script>alert('x')</script>",
                "Body with <b>markup</b> & an ampersand",
                "<img src=x onerror=alert(1)>", "BUY COINS",
                "https://globalfutservices.com/order", null);

        var r = PromotionalEmail.render(hostile, BRAND, delivery());
        assertThat(r.html())
                .doesNotContain("<script>")
                .doesNotContain("onerror=")
                .contains("&lt;script&gt;")
                .contains("&amp;");
    }

    @Test
    @DisplayName("the tracking pixel is present when asked for, absent when not")
    void pixel_is_optional() {
        assertThat(PromotionalEmail.render(sale(), BRAND, delivery()).html())
                .contains(PIXEL)
                .contains("width=\"1\" height=\"1\"");

        // The preview renders with no pixel: looking at a campaign must not record an
        // open against a real recipient.
        assertThat(PromotionalEmail.render(
                sale(), BRAND, new PromotionalEmail.Delivery(UNSUB, null)).html())
                .doesNotContain("/marketing/o/");
    }

    @Test
    @DisplayName("a banner is rendered when present and omitted when not")
    void banner_is_optional() {
        var withBanner = new PromotionalEmail.Campaign(
                "Sale", "HEADING", "Body", null, null, null,
                "https://globalfutservices.com/api/v1/marketing/campaigns/camp_1/banner");
        assertThat(PromotionalEmail.render(withBanner, BRAND, delivery()).html())
                .contains("/marketing/campaigns/camp_1/banner")
                .contains("max-width:552px");

        assertThat(PromotionalEmail.render(sale(), BRAND, delivery()).html())
                .doesNotContain("/marketing/campaigns/");
    }

    @Test
    @DisplayName("it is the same shell the order emails use")
    void shares_the_transactional_template() {
        var r = PromotionalEmail.render(sale(), BRAND, delivery());
        assertThat(r.html())
                .contains("GLOBAL FUT SERVICES")
                .contains("globalfutservices.com")
                .contains("@global_fut_services")
                .contains("SAFETY POLICY")
                .contains("WORRY LESS");
        // And carries none of the claims the reference artwork made up.
        assertThat(r.html()).doesNotContain("2019");
        assertThat(r.html()).doesNotContain("@globalfutservices");
    }
}
