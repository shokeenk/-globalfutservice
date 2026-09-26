package com.globalfutservice.notify.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    private static final String BANNER =
            "https://globalfutservices.com/api/v1/marketing/campaigns/camp_1/banner?v=1";

    /** The reference design's example, field for field. */
    private static PromotionalEmail.Campaign toty() {
        return new PromotionalEmail.Campaign(
                "TOTY IS HERE! Get Your Coins Now",
                "Team of the Year", "TOTY Coins Sale", "Build your dream squad",
                "Team of the Year is here!\nGet your FC 26 coins now.\n\nCode works until the 10th.",
                "15% OFF", "hunter10", LocalDate.of(2026, 10, 10),
                "ORDER NOW", "https://globalfutservices.com/order?service=TRADING_SERVICE",
                BANNER);
    }

    /** Headline and description only — every optional part left out. */
    private static PromotionalEmail.Campaign bare() {
        return new PromotionalEmail.Campaign("News", null, "Server maintenance", null,
                "We will be down for an hour.", null, null, null, null, null, null);
    }

    private static PromotionalEmail.Delivery delivery() {
        return new PromotionalEmail.Delivery(UNSUB, PIXEL);
    }

    private static String html(PromotionalEmail.Campaign c) {
        return PromotionalEmail.render(c, BRAND, delivery()).html();
    }

    @Nested
    class Consent {

        @Test
        @DisplayName("a campaign without an unsubscribe link will not render at all")
        void refusesWithoutOptOut() {
            // The single failure in this system that cannot be undone after the fact: once
            // four hundred people have a promotional email with no way out, no later fix
            // reaches them. So it is refused here rather than defaulted to something.
            assertThatThrownBy(() -> PromotionalEmail.render(
                    toty(), BRAND, new PromotionalEmail.Delivery(null, PIXEL)))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("unsubscribe");
            assertThatThrownBy(() -> PromotionalEmail.render(
                    toty(), BRAND, new PromotionalEmail.Delivery("  ", PIXEL)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("carries the opt-out in both the HTML and the plain-text part")
        void optOutInBothParts() {
            var r = PromotionalEmail.render(toty(), BRAND, delivery());
            assertThat(r.html()).contains("/unsubscribe?t=abc-123&amp;c=camp_1")
                    .containsIgnoringCase("unsubscribe");
            assertThat(r.text()).contains(UNSUB).containsIgnoringCase("unsubscribe");
        }

        @Test
        @DisplayName("says it is marketing, and that order email is unaffected")
        void saysItIsMarketing() {
            assertThat(html(toty()))
                    .contains("opted in")
                    .contains("marketing")
                    .containsIgnoringCase("order updates are sent separately");
        }

        @Test
        @DisplayName("the tracking pixel is present when asked for, absent when not")
        void pixelIsOptional() {
            assertThat(html(toty())).contains(PIXEL).contains("width=\"1\" height=\"1\"");
            assertThat(PromotionalEmail.render(toty(), BRAND,
                    new PromotionalEmail.Delivery(UNSUB, null)).html())
                    .doesNotContain("/marketing/o/");
        }
    }

    @Nested
    class Content {

        @Test
        @DisplayName("renders every part of the reference layout from the admin's fields")
        void rendersEverything() {
            var r = PromotionalEmail.render(toty(), BRAND, delivery());
            assertThat(r.html())
                    .contains("TEAM OF THE YEAR")
                    .contains("BUILD YOUR DREAM SQUAD")
                    .contains("Team of the Year is here!")
                    .contains("USE CODE").contains("HUNTER10")
                    .contains("ORDER NOW &rarr;")
                    .contains("/order?service=TRADING_SERVICE")
                    .contains("Offer valid till 10 Oct 2026.")
                    .contains(BANNER);
            assertThat(r.subject()).isEqualTo("TOTY IS HERE! Get Your Coins Now");
        }

        @Test
        @DisplayName("the headline's last word is the red one")
        void twoToneHeadline() {
            assertThat(html(toty())).contains("TOTY COINS <span style=\"color:#DB1825;\">SALE</span>");
            assertThat(PromotionalEmail.twoTone("Sale"))
                    .isEqualTo("<span style=\"color:#DB1825;\">SALE</span>");
        }

        @Test
        @DisplayName("an offer ending in OFF is drawn as the figure over the word")
        void offerFigure() {
            String h = html(toty());
            assertThat(h).contains(">15%</div>").contains(">OFF</div>");
        }

        @Test
        @DisplayName("an offer in other words is shown as written")
        void offerAsWritten() {
            var c = new PromotionalEmail.Campaign("s", null, "Sale", null, "b",
                    "Free boost", null, null, null, null, null);
            assertThat(html(c)).contains(">FREE BOOST</div>").doesNotContain(">OFF</div>");
        }

        @Test
        @DisplayName("blank lines become paragraphs and single breaks are kept")
        void paragraphs() {
            String h = html(toty());
            assertThat(h).contains("Team of the Year is here!<br/>Get your FC 26 coins now.");
            assertThat(h.split("<p style=").length - 1).isEqualTo(2);
        }

        @Test
        @DisplayName("a campaign with only a headline and a description draws no empty boxes")
        void noEmptyParts() {
            String h = html(bare());
            assertThat(h)
                    .contains("SERVER <span style=\"color:#DB1825;\">MAINTENANCE</span>")
                    .doesNotContain("USE CODE")
                    .doesNotContain("&rarr;")
                    .doesNotContain("Offer valid till")
                    .doesNotContain("/marketing/campaigns/")
                    // The kicker's and subline's own styles, which appear nowhere else.
                    .doesNotContain("letter-spacing:3px;color:#DB1825")
                    .doesNotContain("letter-spacing:3.5px");
        }

        @Test
        @DisplayName("without a banner the copy takes the whole hero")
        void noBannerFullWidth() {
            assertThat(html(bare())).doesNotContain("width=\"60%\"");
            assertThat(html(toty())).contains("width=\"60%\"");
        }

        @Test
        @DisplayName("the plain-text part carries the same offer as the HTML")
        void textPart() {
            String t = PromotionalEmail.render(toty(), BRAND, delivery()).text();
            assertThat(t)
                    .contains("TOTY Coins Sale")
                    .contains("15% OFF")
                    .contains("Use code: HUNTER10")
                    .contains("Offer valid till 10 Oct 2026.")
                    .contains("ORDER NOW: https://globalfutservices.com/order?service=TRADING_SERVICE");
        }
    }

    @Nested
    class Safety {

        @Test
        @DisplayName("admin-typed copy is escaped in every field, not rendered as markup")
        void escapesEverything() {
            var hostile = new PromotionalEmail.Campaign(
                    "Sale", "<b>kick</b>", "<script>alert('x')</script>", "<i>sub</i>",
                    "Body with <b>markup</b> & an ampersand",
                    "<img src=x onerror=alert(1)> OFF", "<svg/onload=1>", null,
                    "BUY", "https://globalfutservices.com/order", null);

            String h = html(hostile);
            // Case-insensitive throughout: the layout uppercases most fields, so a check for
            // the lowercase payload would pass whether or not anything was escaped.
            assertThat(h)
                    .doesNotContainIgnoringCase("<script>")
                    .doesNotContainIgnoringCase("<b>kick</b>")
                    .doesNotContainIgnoringCase("<i>sub</i>")
                    .doesNotContainIgnoringCase("<img src=x")
                    .doesNotContainIgnoringCase("<svg/onload")
                    .contains("&lt;SCRIPT&gt;")
                    .contains("&amp;");
        }

        @Test
        @DisplayName("every image has alt text, so a blocked image never leaves an unlabelled box")
        void everyImageHasAlt() {
            Matcher img = Pattern.compile("<img [^>]*>").matcher(html(toty()));
            int count = 0;
            while (img.find()) {
                count++;
                assertThat(img.group()).contains("alt=\"");
            }
            assertThat(count).isGreaterThanOrEqualTo(7);   // mark, 3 badges, banner, 2 socials
        }

        @Test
        @DisplayName("the business's name is set in type as well as in the mark")
        void nameSurvivesBlockedImages() {
            assertThat(html(toty())).contains(">GLOBAL FUT SERVICES</div>")
                    .contains("alt=\"Global FUT Services\"");
        }

        @Test
        @DisplayName("links only the social accounts the business has")
        void onlyRealSocials() {
            String h = html(toty());
            assertThat(h).contains("instagram.com/global_fut_services")
                    .contains("discord.com/invite/8FeP7C6tXt")
                    .doesNotContainIgnoringCase("youtube")
                    .doesNotContain("x.com")
                    .doesNotContain("twitter");
        }

        @Test
        @DisplayName("makes no claim the storefront does not already make")
        void noNewClaims() {
            String h = html(toty());
            assertThat(h).contains("SAFETY POLICY").contains("TRUSTED BY").contains("WORRY LESS");
            assertThat(h)
                    .doesNotContainIgnoringCase("safe and secure")
                    .doesNotContainIgnoringCase("ultimate fc partner")
                    .doesNotContain("2019");
        }

        @Test
        @DisplayName("is laid out in tables, which is what Outlook can render")
        void tableLayout() {
            String h = html(toty());
            assertThat(h).contains("role=\"presentation\"")
                    .doesNotContain("display:flex")
                    .doesNotContain("display:grid")
                    .doesNotContain("<svg");
        }
    }
}
