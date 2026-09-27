package com.globalfutservice.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.email.TransactionalEmails;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a slot held at checkout tells Discord and the customer: the coach hears about it
 * at once, the customer is told it is booked only once the payment is verified, and a
 * Discord failure is retried but never allowed to fail anything.
 */
class CoachingHoldNotificationTest {

    /** 19:00 on Monday 5 Oct 2026 in India; 09:30 the same morning in New York. */
    private static final Instant START = Instant.parse("2026-10-05T13:30:00Z");

    private static CoachingBookingNotification session(String status, String payment) {
        return new CoachingBookingNotification("ses_x", START, START.plus(Duration.ofMinutes(40)),
                null, "America/New_York", "player@example.test", "Player One", "Vinay", 1, 6,
                "GFS-26-COACH01", payment, "VinayFC10", "PlayStation", "Division 3", status);
    }

    @Nested
    class Discord {

        private DiscordBotClient bot;
        private DiscordNotifier notifier;

        @BeforeEach
        void setUp() {
            AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
            when(props.notifications().discordCoachingChannelId()).thenReturn("coach-ch");
            bot = mock(DiscordBotClient.class);
            when(bot.isEnabled()).thenReturn(true);
            when(bot.findTicketChannel("GFS-26-COACH01")).thenReturn(Optional.of("ticket-ch"));
            notifier = new DiscordNotifier(props, new ObjectMapper(), null, bot);
            notifier.retryDelays = List.of(Duration.ZERO, Duration.ZERO);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> postedTo(String channel) {
            ArgumentCaptor<Map<String, Object>> embed = ArgumentCaptor.forClass(Map.class);
            verify(bot).postEmbed(eq(channel), anyString(), embed.capture());
            return embed.getValue();
        }

        @SuppressWarnings("unchecked")
        private static String field(Map<String, Object> embed, String name) {
            return ((List<Map<String, Object>>) embed.get("fields")).stream()
                    .filter(f -> name.equals(f.get("name")))
                    .map(f -> String.valueOf(f.get("value")))
                    .findFirst().orElse(null);
        }

        @Test
        @DisplayName("a hold is announced as a new booking with the payment pending, with everything the coach needs")
        void hold() {
            notifier.coachingBooked(session("PENDING", "AWAITING_PAYMENT"));

            Map<String, Object> embed = postedTo("coach-ch");
            assertThat(embed.get("title")).isEqualTo("New coaching booking — payment pending");
            assertThat(field(embed, "Session")).isEqualTo("1 of 6");
            assertThat(field(embed, "Order")).contains("GFS-26-COACH01");
            assertThat(field(embed, "In-game ID")).isEqualTo("VinayFC10");
            assertThat(field(embed, "Platform")).isEqualTo("PlayStation");
            assertThat(field(embed, "Rank")).isEqualTo("Division 3");
            assertThat(field(embed, "Payment")).isEqualTo("Awaiting payment");
            assertThat(field(embed, "Starts (IST)")).contains("19:00").contains("Asia/Kolkata");
            assertThat(field(embed, "Starts (customer)")).contains("09:30").contains("America/New_York");
        }

        @Test
        @DisplayName("the session time also goes to the customer's own order ticket")
        void ticket() {
            notifier.coachingBooked(session("PENDING", "AWAITING_PAYMENT"));

            assertThat(postedTo("ticket-ch").get("title"))
                    .isEqualTo("New coaching booking — payment pending");
        }

        @Test
        @DisplayName("payment verified: announced as confirmed")
        void confirmed() {
            notifier.coachingSessionConfirmed(session("SCHEDULED", "PAID"));

            Map<String, Object> embed = postedTo("coach-ch");
            assertThat(embed.get("title")).isEqualTo("Coaching session confirmed");
            assertThat(field(embed, "Payment")).isEqualTo("Paid");
        }

        @Test
        @DisplayName("a released hold is announced as the slot being free again")
        void released() {
            notifier.coachingCancelled(session("RELEASED", "AWAITING_PAYMENT"));

            assertThat(postedTo("coach-ch").get("title")).isEqualTo("Held coaching slot released");
        }

        @Test
        @DisplayName("Discord's own failure is retried, and the retry that works is the last")
        void retriesServerErrors() {
            doThrow(new DiscordBotClient.DiscordException("HTTP 503", 503))
                    .doThrow(new DiscordBotClient.DiscordException("HTTP 503", 503))
                    .doNothing()
                    .when(bot).postEmbed(eq("coach-ch"), anyString(), any());

            notifier.coachingBooked(session("PENDING", "AWAITING_PAYMENT"));

            verify(bot, times(3)).postEmbed(eq("coach-ch"), anyString(), any());
        }

        @Test
        @DisplayName("a refusal that cannot change is not retried")
        void noRetryOnForbidden() {
            doThrow(new DiscordBotClient.DiscordException("HTTP 403 Missing Access", 403))
                    .when(bot).postEmbed(eq("coach-ch"), anyString(), any());

            notifier.coachingBooked(session("PENDING", "AWAITING_PAYMENT"));

            verify(bot, times(1)).postEmbed(eq("coach-ch"), anyString(), any());
        }

        @Test
        @DisplayName("a Discord that never answers never throws into the booking")
        void neverThrows() {
            doThrow(new DiscordBotClient.DiscordException("timed out", 0))
                    .when(bot).postEmbed(anyString(), anyString(), any());

            assertThatCode(() -> notifier.coachingBooked(session("PENDING", "AWAITING_PAYMENT")))
                    .doesNotThrowAnyException();
            verify(bot, times(3)).postEmbed(eq("coach-ch"), anyString(), any());
        }
    }

    @Nested
    class Email {

        private JavaMailSender sender;
        private EmailNotifier notifier;

        @BeforeEach
        void setUp() {
            sender = mock(JavaMailSender.class);
            when(sender.createMimeMessage()).thenAnswer(i ->
                    new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));
            AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
            when(props.notifications().emailEnabled()).thenReturn(true);
            when(props.notifications().emailFrom()).thenReturn("orders@globalfutservices.com");
            when(props.notifications().emailFromName()).thenReturn("Global FUT Services");
            when(props.publicUrl()).thenReturn("https://globalfutservices.com");
            when(props.discordInvite()).thenReturn("https://discord.gg/x");
            when(props.instagramUrl()).thenReturn("https://www.instagram.com/global_fut_services/");
            notifier = new EmailNotifier(props, sender);
        }

        @Test
        @DisplayName("no 'booked' email or calendar invite for a slot that is only held")
        void noEmailForAHold() {
            notifier.coachingBooked(session("PENDING", "AWAITING_PAYMENT"));

            verify(sender, never()).send(any(jakarta.mail.internet.MimeMessage.class));
        }

        @Test
        @DisplayName("the 'booked' email goes out once the payment is verified")
        void emailOnConfirmation() {
            notifier.coachingSessionConfirmed(session("SCHEDULED", "PAID"));

            verify(sender).send(any(jakarta.mail.internet.MimeMessage.class));
        }

        @Test
        @DisplayName("nothing to cancel for a hold the customer was never told was booked")
        void noCancellationForAReleasedHold() {
            notifier.coachingCancelled(session("RELEASED", "AWAITING_PAYMENT"));

            verify(sender, never()).send(any(jakarta.mail.internet.MimeMessage.class));
        }

        @Test
        @DisplayName("a booking made with a credit is emailed as before")
        void creditBookingUnchanged() {
            notifier.coachingBooked(session(null, "PAID"));

            verify(sender).send(any(jakarta.mail.internet.MimeMessage.class));
        }

        @Test
        @DisplayName("an email failure never throws into the booking")
        void emailFailureContained() {
            doThrow(new org.springframework.mail.MailSendException("535"))
                    .when(sender).send(any(jakarta.mail.internet.MimeMessage.class));

            assertThatCode(() -> notifier.coachingSessionConfirmed(session("SCHEDULED", "PAID")))
                    .doesNotThrowAnyException();
        }
    }

    @Nested
    class OrderConfirmation {

        private static final com.globalfutservice.notify.email.EmailTemplate.Brand BRAND =
                new com.globalfutservice.notify.email.EmailTemplate.Brand(
                        "https://globalfutservices.com", "globalfutservices.com",
                        "https://discord.gg/x", "Discord", "https://instagram.com/x", "Instagram");

        private static OrderNotification paid(Instant sessionStart, String zone) {
            return new OrderNotification("GFS-26-COACH01", "PAID", "FUT Classes — 6 sessions",
                    "₹4,050.00", "player@example.test", null, "SCHEDULED_SESSION", "COACHING",
                    "PlayStation", "https://globalfutservices.com/admin/orders/GFS-26-COACH01",
                    "PlayStation · ID VinayFC10", sessionStart, zone);
        }

        @Test
        @DisplayName("carries the session date and time in the customer's own time zone")
        void sessionInCustomerZone() {
            TransactionalEmails.Rendered email = TransactionalEmails.orderConfirmed(
                    paid(START, "America/New_York"), BRAND, "https://x/track", "https://discord.gg/x");

            assertThat(email.text()).contains("Your session: Mon 5 Oct 2026, 9:30 AM (America/New_York)");
            assertThat(email.html()).contains("Your session").contains("9:30 AM");
        }

        @Test
        @DisplayName("says nothing about a session when the order has none")
        void noSession() {
            TransactionalEmails.Rendered email = TransactionalEmails.orderConfirmed(
                    paid(null, null), BRAND, "https://x/track", "https://discord.gg/x");

            assertThat(email.text()).doesNotContain("Your session");
        }
    }
}
