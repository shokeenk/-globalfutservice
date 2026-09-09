package com.globalfutservice.notify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * What actually goes over the wire to Discord.
 *
 * <p>Asserted against a real HTTP server rather than a mocked client, because almost
 * everything worth checking here is a property of the JSON body -- whether a mention is
 * in the one form Discord pings on, whether a customer's sign-in could ever appear, what
 * the allow-list permits. A mocked transport would let all of those be wrong.
 */
class DiscordNotifierTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ADMIN_ID = "1300551868174569595";

    private HttpServer server;
    private BlockingQueue<String> received;
    private int status = 204;

    @BeforeEach
    void startServer() throws IOException {
        received = new ArrayBlockingQueue<>(4);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/webhook", exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String webhookUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/webhook";
    }

    private static AppProperties.Notifications notifications(boolean enabled, String url, String adminId) {
        return new AppProperties.Notifications(
                false, null, null, null, "gfs_new_order", "https://graph.facebook.com/v20.0",
                false, null, null, "https://api.telegram.org",
                enabled, url, adminId,
                null, null, null,
                false, null, "orders@globalfutservices.com", "Global FUT Services");
    }

    private DiscordNotifier notifier(AppProperties.Notifications n) {
        AppProperties props = mock(AppProperties.class);
        when(props.notifications()).thenReturn(n);
        // Null tickets: these tests cover the webhook path, which is what runs when no
        // bot is configured -- and is the fallback when one is configured and fails.
        return new DiscordNotifier(props, MAPPER, null);
    }

    private DiscordNotifier enabled() {
        return notifier(notifications(true, webhookUrl(), ADMIN_ID));
    }

    private JsonNode posted() throws Exception {
        String body = received.poll(5, TimeUnit.SECONDS);
        assertThat(body).as("a request should have reached the webhook").isNotNull();
        return MAPPER.readTree(body);
    }

    /* --------------------------------------------------------------- fixtures --- */

    private static PaymentClaimNotification claim(boolean credentialsHeld) {
        return new PaymentClaimNotification(
                "GFS-26-000123",
                "Champs Boosting — 15 wins",
                "₹3,638.75",
                "UPI",
                "9166172359@ybl",
                "432198765012",
                "buyer@example.com",
                "buyer#1234",
                "Buyer Name",
                credentialsHeld,
                false,
                Instant.parse("2026-09-07T10:15:30Z"),
                "https://globalfutservices.com/admin/orders/GFS-26-000123");
    }

    private static OrderNotification order() {
        return new OrderNotification(
                "GFS-26-000124", "AWAITING_PAYMENT", "FUT Classes — Single session",
                "₹922.50", "buyer@example.com", "buyer#1234", "PLAYER_AUCTION",
                "https://globalfutservices.com/admin/orders/GFS-26-000124");
    }

    /* ------------------------------------------------------------ the payload --- */

    @Nested
    @DisplayName("a reported payment")
    class Claims {

        @Test
        @DisplayName("carries everything needed to go and check the money")
        void carriesTheDetails() throws Exception {
            enabled().paymentClaimed(claim(true));
            String json = posted().toString();

            // The reference is the reason the message exists; the destination says which
            // account to open. Both have to survive into the body verbatim.
            assertThat(json).contains("GFS-26-000123")
                    .contains("432198765012")
                    .contains("9166172359@ybl")
                    .contains("UPI")
                    .contains("Champs Boosting")
                    .contains("3,638.75")
                    .contains("buyer@example.com")
                    .contains("buyer#1234");
        }

        @Test
        @DisplayName("mentions the admin in the form Discord actually pings on")
        void mentionsAdmin() throws Exception {
            enabled().paymentClaimed(claim(true));

            // <@id> is the only form that notifies. A bare id renders as a number and a
            // username renders as text -- both look right and neither pings anyone.
            assertThat(posted().get("content").asText()).startsWith("<@" + ADMIN_ID + ">");
        }

        @Test
        @DisplayName("says whether the sign-in arrived, never what it is")
        void credentialsAreABooleanNotAValue() throws Exception {
            enabled().paymentClaimed(claim(true));

            assertThat(posted().toString()).contains("submitted");
        }

        @Test
        @DisplayName("still sends when no admin id is configured, just without the ping")
        void sendsWithoutAdminId() throws Exception {
            notifier(notifications(true, webhookUrl(), null)).paymentClaimed(claim(false));

            JsonNode body = posted();
            // Losing the ping is a degraded alert. Losing the alert would be an unpaid
            // order nobody hears about, which is the thing this channel exists to stop.
            assertThat(body.get("content").asText()).doesNotContain("<@");
            assertThat(body.toString()).contains("432198765012");
            assertThat(body.get("allowed_mentions").get("users")).isEmpty();
        }

        @Test
        @DisplayName("cannot be made to ping a whole server by order text")
        void mentionsAreAllowListed() throws Exception {
            PaymentClaimNotification hostile = new PaymentClaimNotification(
                    "GFS-26-000125", "@everyone @here", "₹1.00", "UPI", "x@y",
                    "999999999999", "a@b.com", "@everyone", "@everyone", false, false,
                    Instant.now(), "https://example.test/admin");

            enabled().paymentClaimed(hostile);
            JsonNode allowed = posted().get("allowed_mentions");

            /*
             * Service labels, customer handles and references are all free text that a
             * customer can influence. Without the allow-list, "@everyone" in any of them
             * notifies every member of the server -- from a webhook that is meant to
             * whisper to one operator.
             */
            assertThat(allowed.get("parse")).isEmpty();
            assertThat(allowed.get("users")).hasSize(1);
            assertThat(allowed.get("users").get(0).asText()).isEqualTo(ADMIN_ID);
        }
    }

    /* ------------------------------------------------------- the vault boundary --- */

    @Nested
    @DisplayName("EA credentials")
    class Credentials {

        /**
         * Checked structurally rather than by inspecting one message. A field that does
         * not exist cannot be sent by any future edit to the formatting.
         */
        @Test
        @DisplayName("have no field to travel in")
        void notificationsCannotCarryThem() {
            for (Class<?> type : List.of(PaymentClaimNotification.class, OrderNotification.class)) {
                for (RecordComponent component : type.getRecordComponents()) {
                    String name = component.getName().toLowerCase(Locale.ROOT);
                    boolean looksLikeASecret =
                            name.contains("password")
                                    || name.contains("backup")
                                    || name.contains("eaemail")
                                    || name.contains("secret")
                                    // "credentialsHeld" is a boolean and is allowed; a
                                    // String named for credentials would not be.
                                    || (name.contains("credential")
                                            && component.getType() != boolean.class);

                    assertThat(looksLikeASecret)
                            .as("%s.%s would let a sign-in reach an operator alert",
                                    type.getSimpleName(), component.getName())
                            .isFalse();
                }
            }
        }

        @Test
        @DisplayName("do not appear in a sent message even when the vault holds them")
        void neverInThePayload() throws Exception {
            enabled().paymentClaimed(claim(true));
            String json = posted().toString().toLowerCase(Locale.ROOT);

            // The credential-shaped things a real order carries. None has a route here,
            // and this asserts that rather than trusting it.
            assertThat(json)
                    .doesNotContain("password")
                    .doesNotContain("backupcode")
                    .doesNotContain("backup code")
                    .doesNotContain("eapassword")
                    .doesNotContain("eaemail");
        }
    }

    /* -------------------------------------------------------------- behaviour --- */

    @Nested
    @DisplayName("when things go wrong")
    class Failure {

        @Test
        @DisplayName("a webhook that rejects the post does not throw")
        void rejectedPostIsSwallowed() {
            status = 404;   // what a deleted webhook returns

            assertThatCode(() -> enabled().paymentClaimed(claim(true)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("an unreachable webhook does not throw")
        void unreachableIsSwallowed() {
            // Nothing is listening on this port. The customer has already sent money; an
            // alert that cannot be delivered must not undo their claim.
            DiscordNotifier offline = notifier(
                    notifications(true, "http://127.0.0.1:1/webhook", ADMIN_ID));

            assertThatCode(() -> offline.paymentClaimed(claim(true)))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("a malformed webhook URL does not throw")
        void malformedUrlIsSwallowed() {
            DiscordNotifier broken = notifier(notifications(true, "not-a-url", ADMIN_ID));

            assertThatCode(() -> broken.paymentClaimed(claim(true)))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("switching the channel off")
    class Disabled {

        @Test
        @DisplayName("sends nothing when disabled")
        void disabledSendsNothing() throws Exception {
            notifier(notifications(false, webhookUrl(), ADMIN_ID)).paymentClaimed(claim(true));

            assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
        }

        @Test
        @DisplayName("is not enabled by the flag alone, without a webhook")
        void flagWithoutWebhookIsNotEnabled() {
            assertThat(notifier(notifications(true, null, ADMIN_ID)).isEnabled()).isFalse();
            assertThat(notifier(notifications(true, "  ", ADMIN_ID)).isEnabled()).isFalse();
        }

        @Test
        @DisplayName("is enabled without an admin id, because the alert still lands")
        void enabledWithoutAdminId() {
            assertThat(notifier(notifications(true, webhookUrl(), null)).isEnabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("other order events")
    class OtherEvents {

        @Test
        @DisplayName("new orders are posted, but without a ping")
        void newOrdersDoNotPing() throws Exception {
            enabled().orderPlaced(order());

            // Most orders that reach this point are never paid for. A channel that pings
            // on all of them is one whose pings get muted -- taking the payment alerts
            // with them.
            JsonNode body = posted();
            assertThat(body.get("content").asText()).doesNotContain("<@");
            assertThat(body.toString()).contains("GFS-26-000124").contains("FUT Classes");
        }

        @Test
        @DisplayName("a paid order pings, because somebody has to start work")
        void paidOrdersPing() throws Exception {
            enabled().readyToFulfil(order());

            assertThat(posted().get("content").asText()).startsWith("<@" + ADMIN_ID + ">");
        }
    }
}
