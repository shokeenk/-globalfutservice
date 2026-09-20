package com.globalfutservice.notify.email;

import com.globalfutservice.notify.OrderNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two customer emails, and the claims they must not make.
 *
 * <p>Half of this file asserts the presence of things. The other half asserts the absence
 * of four lines that came in with the reference artwork and are not true of this business
 * as far as anything in the repository shows — a founding year, an Instagram handle that
 * is not the one the site links to, and two taglines that appear nowhere else. Those are
 * the assertions worth having: content that is merely missing gets noticed, content that
 * is wrong gets published and stays published.
 */
class TransactionalEmailsTest {

    private static final EmailTemplate.Brand BRAND = new EmailTemplate.Brand(
            "https://globalfutservices.com", "globalfutservices.com",
            "https://discord.com/invite/8FeP7C6tXt", "Join the GFS Discord",
            "https://www.instagram.com/global_fut_services/", "@global_fut_services");

    private static final String TRACK = "https://globalfutservices.com/track?ref=GFS-26-ABC123";
    private static final String DISCORD = "https://discord.com/invite/8FeP7C6tXt";

    private static OrderNotification order(String sku, String serviceLabel, String platform) {
        return new OrderNotification("GFS-26-ABC123", "PAID", serviceLabel, "₹1,640.00",
                "player@example.test", null, "PLAYER_AUCTION", sku, platform,
                "https://globalfutservices.com/admin/orders/GFS-26-ABC123", null);
    }

    private static OrderNotification coins() {
        return order("TRADING_SERVICE", "Buy Coins — 100K (PC)", "PC");
    }

    private static OrderNotification champs() {
        return order("BOOST_CHAMPS", "Champs Boosting — 11 wins", "PlayStation");
    }

    @Nested
    @DisplayName("Email 1 — awaiting verification")
    class AwaitingVerification {

        @Test
        @DisplayName("says what was received, and does not say it is confirmed")
        void states_the_right_status() {
            var r = TransactionalEmails.awaitingVerification(coins(), BRAND, TRACK);

            assertThat(r.subject()).isEqualTo("Your GFS Order Is Awaiting Verification");
            assertThat(r.html())
                    .contains("YOUR GFS ORDER IS AWAITING VERIFICATION")
                    .contains("Awaiting Verification")
                    .contains("#GFS-26-ABC123")
                    .contains("TRACK YOUR ORDER")
                    .contains(TRACK);

            // The whole point of this email is that nobody has checked yet. If the word
            // "Confirmed" ever appears here, the customer has been told their payment
            // cleared before an operator looked at it.
            assertThat(r.html()).doesNotContain("Confirmed");
            assertThat(r.text()).doesNotContain("Confirmed");
        }

        @Test
        @DisplayName("the plain-text part carries the same facts as the HTML")
        void text_alternative_is_not_a_stub() {
            var r = TransactionalEmails.awaitingVerification(coins(), BRAND, TRACK);
            assertThat(r.text())
                    .contains("GFS-26-ABC123")
                    .contains("Buy Coins — 100K (PC)")
                    .contains("Awaiting Verification")
                    .contains(TRACK);
        }
    }

    @Nested
    @DisplayName("Email 2 — confirmed")
    class Confirmed {

        @Test
        @DisplayName("coins get a tracking link and no Discord steps")
        void coins_branch() {
            var r = TransactionalEmails.orderConfirmed(coins(), BRAND, TRACK, DISCORD);

            assertThat(r.subject()).isEqualTo("Your GFS Order Is Confirmed");
            assertThat(r.html())
                    .contains("YOUR GFS ORDER IS CONFIRMED!")
                    .contains("Confirmed")
                    .contains("TRACK YOUR ORDER")
                    .contains(TRACK)
                    .contains("₹1,640.00")
                    .contains("Platform");

            // A coin order is fulfilled without the customer. Sending them to Discord to
            // wait for an operations executive who has nothing to say would be wrong.
            assertThat(r.html()).doesNotContain("NEXT STEPS");
            assertThat(r.html()).doesNotContain("JOIN DISCORD");
        }

        @ParameterizedTest
        @ValueSource(strings = {"BOOST_CHAMPS", "BOOST_RIVALS", "COACHING"})
        @DisplayName("everything else gets the three-step Discord block")
        void discord_branch(String sku) {
            var r = TransactionalEmails.orderConfirmed(
                    order(sku, "A service", "PlayStation"), BRAND, TRACK, DISCORD);

            assertThat(r.html())
                    .contains("NEXT STEPS")
                    .contains("Join our Discord server")
                    .contains("Your ticket will be created")
                    .contains("Our Operations Executive will contact you")
                    .contains("JOIN DISCORD")
                    .contains(DISCORD)
                    .contains("same email/Discord account used for placing the order");

            // The tracking button belongs to the coins branch only; these customers are
            // told to go to Discord, and two competing primary actions is one too many.
            assertThat(r.html()).doesNotContain("TRACK YOUR ORDER");
        }

        @Test
        @DisplayName("platform is omitted rather than printed blank when the SKU has none")
        void platform_optional() {
            var r = TransactionalEmails.orderConfirmed(
                    order("COACHING", "1-to-1 Coaching", null), BRAND, TRACK, DISCORD);
            assertThat(r.html()).doesNotContain("Platform");
            assertThat(r.text()).doesNotContain("Platform");
        }
    }

    @Nested
    @DisplayName("the shared shell")
    class Shell {

        @Test
        @DisplayName("both emails carry the same footer, badges and links")
        void one_template_not_two() {
            for (var r : new TransactionalEmails.Rendered[]{
                    TransactionalEmails.awaitingVerification(coins(), BRAND, TRACK),
                    TransactionalEmails.orderConfirmed(champs(), BRAND, TRACK, DISCORD)}) {
                assertThat(r.html())
                        .contains("GLOBAL FUT SERVICES")
                        .contains("globalfutservices.com")
                        .contains("@global_fut_services")
                        .contains(DISCORD)
                        // The wording the site uses in nineteen places.
                        .contains("SAFETY POLICY")
                        .contains("RELIABLE SERVICE")
                        .contains("TRUSTED BY")
                        .contains("WORRY LESS");
            }
        }

        @Test
        @DisplayName("no unverified brand claims are published")
        void omits_unverified_claims() {
            for (var r : new TransactionalEmails.Rendered[]{
                    TransactionalEmails.awaitingVerification(coins(), BRAND, TRACK),
                    TransactionalEmails.orderConfirmed(champs(), BRAND, TRACK, DISCORD)}) {
                // Not claimed anywhere on the site. A founding year is a factual assertion
                // about the business and this one has no source.
                assertThat(r.html()).doesNotContain("2019");
                assertThat(r.html()).doesNotContainIgnoringCase("SINCE 20");
                // The artwork's handle. The site links to global_fut_services; publishing
                // the other one sends customers to an account this business may not own.
                assertThat(r.html()).doesNotContain("@globalfutservices");
                assertThat(r.html()).doesNotContainIgnoringCase("ULTIMATE FC PARTNER");
                assertThat(r.html()).doesNotContainIgnoringCase("PLAY MORE ·");
            }
        }

        @Test
        @DisplayName("transactional mail carries no unsubscribe link")
        void no_unsubscribe_on_transactional() {
            var r = TransactionalEmails.orderConfirmed(champs(), BRAND, TRACK, DISCORD);
            // Order email is sent because somebody ordered, not because they opted into
            // marketing. An unsubscribe link here would offer to turn off the message
            // that tells them their payment failed.
            assertThat(r.html()).doesNotContainIgnoringCase("unsubscribe");
            assertThat(r.html()).contains("not marketing");
        }

        @Test
        @DisplayName("interpolated values are escaped")
        void escapes_everything() {
            var hostile = order("TRADING_SERVICE",
                    "Coins <script>alert('x')</script> & \"more\"", "PC");
            var r = TransactionalEmails.orderConfirmed(hostile, BRAND, TRACK, DISCORD);

            // Promotional copy typed by an admin lands in this same template, so the
            // escaping has to hold for content the caller did not sanitise.
            assertThat(r.html()).doesNotContain("<script>");
            assertThat(r.html()).contains("&lt;script&gt;").contains("&amp;");
        }

        /**
         * Writes each variant to {@code target/email-preview/} for a human to open.
         *
         * <p>Not an assertion — a rendering. Email layout is one of the few things a test
         * genuinely cannot check: whether the banner reads well, whether the step cards
         * stack sensibly, whether the red is the right red. This puts the real output
         * somewhere a person can look at it after any change to the template, without
         * having to send mail to find out.
         */
        @Test
        @DisplayName("renders every variant to target/email-preview for review")
        void writes_previews() throws Exception {
            var dir = java.nio.file.Path.of("target", "email-preview");
            java.nio.file.Files.createDirectories(dir);

            record Variant(String name, TransactionalEmails.Rendered rendered) {
            }
            var variants = new Variant[]{
                    new Variant("1-awaiting-verification",
                            TransactionalEmails.awaitingVerification(coins(), BRAND, TRACK)),
                    new Variant("2-confirmed-coins",
                            TransactionalEmails.orderConfirmed(coins(), BRAND, TRACK, DISCORD)),
                    new Variant("2-confirmed-champs",
                            TransactionalEmails.orderConfirmed(champs(), BRAND, TRACK, DISCORD)),
                    new Variant("2-confirmed-coaching",
                            TransactionalEmails.orderConfirmed(
                                    order("COACHING", "1-to-1 Coaching — Single session", null),
                                    BRAND, TRACK, DISCORD)),
            };
            for (var v : variants) {
                java.nio.file.Files.writeString(dir.resolve(v.name() + ".html"),
                        v.rendered().html());
                java.nio.file.Files.writeString(dir.resolve(v.name() + ".txt"),
                        v.rendered().text());
            }
            assertThat(dir.resolve("2-confirmed-champs.html")).exists();
        }

        @Test
        @DisplayName("a preheader is set, so the inbox preview is not the wordmark")
        void preheader_present() {
            var r = TransactionalEmails.awaitingVerification(coins(), BRAND, TRACK);
            assertThat(r.html()).contains("our team is verifying");
        }
    }
}
