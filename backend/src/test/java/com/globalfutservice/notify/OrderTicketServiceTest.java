package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The ticket a payment submission opens.
 *
 * <p>Two properties matter more than the wording. It must never claim a screenshot is
 * attached when none is — an operator who trusts that goes looking for an image that does
 * not exist, or worse, asks a customer to send something they already sent. And it must
 * never carry an EA sign-in, for the same reason every other alert in this package does
 * not: Discord is plaintext, retained by a third party and readable by everyone in the
 * channel, which is the opposite of the vault the values came from.
 */
class OrderTicketServiceTest {

    private static final String ADMIN_ID = "1300551868174569595";

    private DiscordBotClient bot;
    private OrderTicketService tickets;

    private static AppProperties.Notifications notifications(String botToken) {
        return new AppProperties.Notifications(
                false, null, null, null, "gfs_new_order", "https://graph.facebook.com/v20.0",
                false, null, null, "https://api.telegram.org",
                true, "https://discord.test/webhook", ADMIN_ID,
                botToken, "guild-1", "category-1",
                false, null, "orders@globalfutservices.com", "Global FUT Services");
    }

    @BeforeEach
    void setUp() {
        bot = mock(DiscordBotClient.class);
        AppProperties props = mock(AppProperties.class);
        when(props.notifications()).thenReturn(notifications("bot-token"));
        when(bot.isEnabled()).thenReturn(true);
        tickets = new OrderTicketService(bot, props);
    }

    private static PaymentClaimNotification claim(boolean hasProof, boolean credentialsHeld) {
        return new PaymentClaimNotification(
                "GFS-26-000123",
                "Champs Boosting — 15 wins",
                "₹3,638.75",
                "UPI",
                "9166172359@ybl",
                "432198765012",
                "buyer@example.com",
                "buyer#1234",
                "Ravi Kumar",
                credentialsHeld,
                hasProof,
                Instant.parse("2026-09-08T10:42:00Z"),
                "https://globalfutservices.com/admin/orders/GFS-26-000123");
    }

    @Nested
    @DisplayName("the ticket body")
    class Body {

        @Test
        @DisplayName("carries every field from real order data")
        void carriesTheFields() {
            String body = tickets.compose(claim(false, true));

            assertThat(body)
                    .contains("NEW PAYMENT SUBMISSION")
                    .contains("#GFS-26-000123")
                    .contains("Ravi Kumar")
                    .contains("buyer@example.com")
                    .contains("Champs Boosting — 15 wins")
                    .contains("₹3,638.75")
                    .contains("UPI")
                    .contains("9166172359@ybl")
                    .contains("432198765012")
                    .contains("PAYMENT VERIFICATION PENDING")
                    .contains("https://globalfutservices.com/admin/orders/GFS-26-000123");
        }

        @Test
        @DisplayName("stamps the time in the operator's own timezone, not UTC")
        void stampIsLocal() {
            // 10:42 UTC is 16:12 in Asia/Kolkata. An operator comparing this against a bank
            // statement reads local times on both, so a UTC stamp costs a conversion every
            // single time.
            assertThat(tickets.compose(claim(false, false))).contains("8 Sept 2026, 4:12 PM");
        }

        @Test
        @DisplayName("says a screenshot is attached only when one actually is")
        void screenshotLineIsHonest() {
            assertThat(tickets.compose(claim(true, false))).contains("attached below");

            String without = tickets.compose(claim(false, false));
            assertThat(without).doesNotContain("attached below");
            assertThat(without).contains("not attached yet");
        }

        @Test
        @DisplayName("mentions the admin in the form Discord pings on")
        void mentionsAdmin() {
            assertThat(tickets.compose(claim(false, false))).startsWith("<@" + ADMIN_ID + ">");
        }
    }

    @Nested
    @DisplayName("EA credentials")
    class Credentials {

        @Test
        @DisplayName("are never in the ticket, only the fact that they arrived")
        void neverInTheBody() {
            String body = tickets.compose(claim(false, true)).toLowerCase(Locale.ROOT);

            assertThat(body).contains("submitted");
            assertThat(body)
                    .doesNotContain("password")
                    .doesNotContain("backup code")
                    .doesNotContain("backupcode")
                    .doesNotContain("eapassword")
                    .doesNotContain("eaemail");
        }
    }

    @Nested
    @DisplayName("when Discord will not cooperate")
    class Fallback {

        @Test
        @DisplayName("a refused channel creation reports empty rather than throwing")
        void refusedCreationFallsBack() {
            // 403 here is almost always the bot missing Manage Channels. The caller has to
            // be able to fall through to the webhook, so this must not propagate.
            when(bot.createTicketChannel(anyString()))
                    .thenThrow(new DiscordBotClient.DiscordException("HTTP 403"));

            assertThat(tickets.openTicket(claim(false, false))).isEmpty();
        }

        @Test
        @DisplayName("a channel that is created but will not accept a post still falls back")
        void refusedPostFallsBack() {
            when(bot.createTicketChannel(anyString())).thenReturn("channel-9");
            org.mockito.Mockito.doThrow(new DiscordBotClient.DiscordException("HTTP 429"))
                    .when(bot).postMessage(anyString(), anyString());

            assertThat(tickets.openTicket(claim(false, false))).isEmpty();
        }

        @Test
        @DisplayName("does nothing at all when no bot is configured")
        void disabledDoesNothing() {
            when(bot.isEnabled()).thenReturn(false);

            assertThat(tickets.openTicket(claim(false, false))).isEmpty();
            verify(bot, never()).createTicketChannel(anyString());
        }

        @Test
        @DisplayName("a failed screenshot upload never propagates")
        void screenshotFailureIsSwallowed() {
            when(bot.findTicketChannel(anyString())).thenReturn(Optional.of("channel-9"));
            org.mockito.Mockito.doThrow(new DiscordBotClient.DiscordException("HTTP 500"))
                    .when(bot).postImage(anyString(), any(), anyString(), anyString());

            // The image is already stored by this point. A Discord outage must not turn a
            // successful upload into a failed one for the customer.
            assertThatCode(() -> tickets.attachScreenshot("GFS-26-000123", new byte[]{1}, "image/png"))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    @DisplayName("channel naming")
    class Naming {

        @Test
        @DisplayName("matches the existing ticket pattern and survives Discord's rules")
        void nameIsSafe() {
            // Discord lowercases and strips punctuation itself. Doing it here means the
            // name we ask for is the name we get -- which matters because the screenshot
            // upload finds the channel again by name.
            assertThat(DiscordBotClient.channelNameFor("GFS-26-000123"))
                    .isEqualTo("order-gfs-26-000123");
            assertThat(DiscordBotClient.channelNameFor("GFS 26/000123"))
                    .isEqualTo("order-gfs-26-000123");
        }

        @Test
        @DisplayName("never exceeds Discord's hundred-character ceiling")
        void nameIsClamped() {
            assertThat(DiscordBotClient.channelNameFor("X".repeat(200)).length())
                    .isLessThanOrEqualTo(100);
        }
    }
}
