package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every email a coin order sends links to that order's own tracking page: a TRACK YOUR ORDER
 * button in the HTML and the plain URL in the text.
 *
 * <p>One link, the one the payment emails and the in-app notices already use:
 * GFS_PUBLIC_URL + {@code /track?ref=} + the order reference. A signed-in owner lands on the
 * order; a guest lands on the lookup with the reference filled in, and gives the order's
 * email there -- never in the link.
 */
class CoinEmailTrackingLinkTest {

    /** Deliberately not the production domain: the link has to come from GFS_PUBLIC_URL. */
    private static final String PUBLIC_URL = "https://shop.example.test";
    private static final String EMAIL = "rahul.private+coins@example.test";
    private static final String DISCORD_HANDLE = "rahul#4242";
    private static final String INSTRUCTION = "Your backup codes have been used or were not accepted. Please create "
            + "new ones in your EA account and enter them on your order page.";

    private static OrderNotification coins(String ref, String status) {
        return new OrderNotification(ref, status, "Buy Coins — 500K (PlayStation)", "₹8,250.00",
                EMAIL, DISCORD_HANDLE, "COMFORT_TRADE", "TRADING_SERVICE", "PlayStation",
                PUBLIC_URL + "/admin/orders/" + ref, null, null, null);
    }

    private static EmailNotifier notifier(JavaMailSender sender) {
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.notifications().emailEnabled()).thenReturn(true);
        when(props.notifications().emailFrom()).thenReturn("orders@globalfutservices.com");
        when(props.notifications().emailFromName()).thenReturn("Global FUT Services");
        when(props.notifications().discordAdminId()).thenReturn("1300551868174569595");
        when(props.publicUrl()).thenReturn(PUBLIC_URL);
        when(props.discordInvite()).thenReturn("https://discord.gg/8FeP7C6tXt");
        when(props.instagramUrl()).thenReturn("https://www.instagram.com/global_fut_services/");
        return new EmailNotifier(props, sender);
    }

    /** Every email a coin order can send, as the notifier sends it, for a given order reference. */
    static Stream<Named<BiConsumer<EmailNotifier, String>>> coinEmails() {
        return Stream.of(
                Named.of("awaiting verification",
                        (e, ref) -> e.awaitingVerification(coins(ref, "AWAITING_PAYMENT"))),
                Named.of("confirmed",
                        (e, ref) -> e.orderConfirmed(coins(ref, "READY_FOR_DELIVERY"))),
                Named.of("sign-in needed",
                        (e, ref) -> e.credentialsNeeded(coins(ref, "CREDENTIALS_PENDING"))),
                Named.of("sign-in reminder",
                        (e, ref) -> e.credentialsReminder(coins(ref, "CREDENTIALS_PENDING"))),
                Named.of("on hold, action needed",
                        (e, ref) -> e.customerActionNeeded(new CustomerActionNotification(coins(ref, "ON_HOLD"),
                                INSTRUCTION))),
                Named.of("delivered",
                        (e, ref) -> e.orderDelivered(coins(ref, "DELIVERED"))));
    }

    private static SentEmail send(BiConsumer<EmailNotifier, String> email, String ref) {
        JavaMailSender sender = SentEmail.sender();
        email.accept(notifier(sender), ref);
        return SentEmail.captured(sender);
    }

    private static String trackUrl(String ref) {
        return PUBLIC_URL + "/track?ref=" + ref;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coinEmails")
    @DisplayName("a TRACK YOUR ORDER button and the plain link, both to this order's own tracking page")
    void buttonAndText(BiConsumer<EmailNotifier, String> email) {
        for (String ref : List.of("GFS-26-COIN0001", "GFS-26-COIN0002")) {
            SentEmail mail = send(email, ref);

            assertThat(mail.to()).isEqualTo(EMAIL);
            assertThat(button(mail.html(), "TRACK YOUR ORDER")).as("the button's link").isEqualTo(trackUrl(ref));
            assertThat(mail.text()).as("the plain-text part").contains(trackUrl(ref));
            // Every tracking link in either part is this order's -- never another order's.
            assertThat(trackingLinks(mail)).as("tracking links").containsExactly(trackUrl(ref));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("coinEmails")
    @DisplayName("the tracking link carries the order reference and nothing else; no link carries anything about "
            + "the customer")
    void nothingSensitive(BiConsumer<EmailNotifier, String> email) {
        SentEmail mail = send(email, "GFS-26-COIN0001");

        URI track = URI.create(trackingLinks(mail).iterator().next());
        assertThat(track.getScheme() + "://" + track.getHost()).isEqualTo(PUBLIC_URL);
        assertThat(track.getPath()).isEqualTo("/track");
        assertThat(track.getQuery()).isEqualTo("ref=GFS-26-COIN0001");

        for (String link : links(mail)) {
            assertThat(link).as("a link in the email")
                    .doesNotContain(EMAIL)
                    .doesNotContain(URLEncoder.encode(EMAIL, StandardCharsets.UTF_8))
                    .doesNotContain("rahul")
                    .doesNotContainIgnoringCase("password")
                    .doesNotContainIgnoringCase("backup")
                    .doesNotContain("@");
        }
    }

    @Test
    @DisplayName("one tracking link for every coin email: the payment emails' own, not a second kind")
    void oneLink() {
        Set<String> seen = new LinkedHashSet<>();
        coinEmails().forEach(email -> seen.addAll(trackingLinks(send(email.getPayload(), "GFS-26-COIN0001"))));
        assertThat(seen).containsExactly(trackUrl("GFS-26-COIN0001"));
    }

    @Test
    @DisplayName("the delivery email keeps the Terms' words in both parts")
    void deliveryNotice() {
        SentEmail mail = send((e, ref) -> e.orderDelivered(coins(ref, "DELIVERED")), "GFS-26-COIN0001");
        assertThat(mail.subject()).isEqualTo("Order GFS-26-COIN0001 delivered");
        assertThat(mail.text())
                .contains("Please note that under our Terms, receipt of this email closes the refund\n"
                        + "window for this order.")
                .contains("Our seven-day guarantee runs from today");
        assertThat(mail.html())
                .contains("Please note that under our Terms, receipt of this email closes the refund window for this "
                        + "order.")
                .contains("Our seven-day guarantee runs from today");
    }

    /*
     * Other orders are not changed: their sign-in request and delivery notice are still the
     * plain text they were, word for word.
     */

    private static OrderNotification boosting() {
        return new OrderNotification("GFS-26-BOOST001", "DELIVERED", "Champs Boosting — 14 wins", "₹2,500.00",
                "player@example.test", null, null, "BOOST_CHAMPS", "PlayStation", null, null, null, null);
    }

    private static SimpleMailMessage plain(BiConsumer<EmailNotifier, OrderNotification> email) {
        JavaMailSender sender = mock(JavaMailSender.class);
        email.accept(notifier(sender), boosting());
        ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        return message.getValue();
    }

    @Test
    @DisplayName("a boosting order's sign-in request is unchanged")
    void boostingSignInUnchanged() {
        SimpleMailMessage mail = plain(EmailNotifier::credentialsNeeded);
        assertThat(mail.getSubject()).isEqualTo("Action needed on order GFS-26-BOOST001");
        assertThat(mail.getText()).isEqualTo("""
                Your order is paid and queued. To start, we need a few details from you.

                Reference: GFS-26-BOOST001

                Add them here: https://shop.example.test/track

                Before you do, please make sure your account is signed out everywhere —
                console, web app and companion app — your transfer market is unlocked, and
                you have fewer than five unassigned items. Those four things account for
                almost every delayed order.

                — Global FUT Services
                """);
    }

    @Test
    @DisplayName("a boosting order's delivery notice is unchanged")
    void boostingDeliveryUnchanged() {
        SimpleMailMessage mail = plain(EmailNotifier::orderDelivered);
        assertThat(mail.getSubject()).isEqualTo("Order GFS-26-BOOST001 delivered");
        assertThat(mail.getText()).isEqualTo("""
                Your order is complete.

                Reference: GFS-26-BOOST001
                Service:   Champs Boosting — 14 wins
                Total:     ₹2,500.00

                Two things worth doing now:

                  1. Change your EA password and regenerate your backup codes. We have
                     already deleted everything you gave us, and rotating is good hygiene
                     regardless.
                  2. Keep this email. Our seven-day guarantee runs from today — if
                     anything happens to the account in that window, reply to this message.

                Please note that under our Terms, receipt of this email closes the refund
                window for this order.

                — Global FUT Services
                """);
    }

    // ---------------------------------------------------------------- reading links

    private static final Pattern ANCHOR = Pattern.compile("<a\\s[^>]*href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>]+");

    /** The destination of the one button labelled {@code label}. */
    private static String button(String html, String label) {
        List<String> found = new ArrayList<>();
        Matcher m = ANCHOR.matcher(html);
        while (m.find()) {
            if (m.group(2).replaceAll("<[^>]+>", "").trim().equals(label)) {
                found.add(unescape(m.group(1)));
            }
        }
        assertThat(found).as("buttons labelled " + label).hasSize(1);
        return found.get(0);
    }

    /** Every link in the email: each href in the HTML and each URL in the text. */
    private static Set<String> links(SentEmail mail) {
        Set<String> out = new LinkedHashSet<>();
        Matcher a = ANCHOR.matcher(mail.html());
        while (a.find()) out.add(unescape(a.group(1)));
        Matcher u = URL.matcher(mail.text());
        while (u.find()) out.add(u.group());
        return out;
    }

    /** The links to a tracking page. */
    private static Set<String> trackingLinks(SentEmail mail) {
        Set<String> out = new LinkedHashSet<>();
        for (String link : links(mail)) {
            if (link.contains("/track")) out.add(link);
        }
        return out;
    }

    private static String unescape(String href) {
        return href.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'");
    }
}
