package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Instant;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a coaching order tells the people who have to act on it.
 *
 * <p>Two audiences. The customer needs the Discord invite once their payment is confirmed,
 * because that is where the session is arranged. The coach and operator need the details
 * the customer gave at checkout -- platform, in-game ID, rank, focus -- in the ticket they
 * work from, instead of asking for them again.
 */
class CoachingNotificationTest {

    private static final String INVITE = "https://discord.com/invite/8FeP7C6tXt";

    private static OrderNotification coachingOrder() {
        return new OrderNotification(
                "GFS-26-COACH01", "PAID", "FUT Classes — Single session · 1 hour", "₹1,000.00",
                "player@example.com", null, "SCHEDULED_SESSION",
                "COACHING", "PlayStation",
                "https://globalfutservices.com/admin/orders/GFS-26-COACH01",
                "PlayStation · ID VinayFC10");
    }

    private static EmailNotifier notifierSending(JavaMailSender sender) {
        AppProperties props = mock(AppProperties.class);
        AppProperties.Notifications notifications = mock(AppProperties.Notifications.class);
        when(props.notifications()).thenReturn(notifications);
        when(notifications.emailEnabled()).thenReturn(true);
        when(notifications.emailFrom()).thenReturn("orders@globalfutservices.com");
        when(notifications.emailFromName()).thenReturn("Global FUT Services");
        when(props.discordInvite()).thenReturn(INVITE);
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(props.instagramUrl()).thenReturn("https://www.instagram.com/global_fut_services/");
        return new EmailNotifier(props, sender);
    }

    @Test
    @DisplayName("the confirmation email carries the Discord invite and the order reference")
    void confirmationEmailCarriesInvite() {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenReturn(
                new jakarta.mail.internet.MimeMessage((jakarta.mail.Session) null));

        // The coaching customer's invite now arrives in the shared confirmation email,
        // which covers Champs, boosting and coaching with one Discord branch.
        notifierSending(sender).orderConfirmed(coachingOrder());

        ArgumentCaptor<jakarta.mail.internet.MimeMessage> sent =
                ArgumentCaptor.forClass(jakarta.mail.internet.MimeMessage.class);
        verify(sender).send(sent.capture());
        assertThat(bodyOf(sent.getValue())).contains(INVITE).contains("GFS-26-COACH01");
    }

    /**
     * No second email for coaching.
     *
     * <p>{@code coachingConfirmed} used to send its own "join us on Discord" message. The
     * shared confirmation email now says that, so this one is silent — otherwise a
     * coaching customer gets two confirmations a second apart.
     */
    @Test
    @DisplayName("the retired coaching email sends nothing")
    void coachingConfirmedIsSilent() {
        JavaMailSender sender = mock(JavaMailSender.class);
        notifierSending(sender).coachingConfirmed(coachingOrder());
        org.mockito.Mockito.verifyNoInteractions(sender);
    }

    /** The whole MIME body as a string, both alternative parts included. */
    private static String bodyOf(jakarta.mail.internet.MimeMessage message) {
        try {
            var out = new java.io.ByteArrayOutputStream();
            message.writeTo(out);
            // Quoted-printable soft-wraps long URLs; unwrap before matching.
            return out.toString(java.nio.charset.StandardCharsets.UTF_8)
                    .replaceAll("[\\r\\n]", "").replace("=3D", "=");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("the payment ticket shows the coaching details when there are some, and nothing otherwise")
    void ticketCarriesCoachingDetails() {
        AppProperties props = mock(AppProperties.class);
        when(props.notifications()).thenReturn(mock(AppProperties.Notifications.class));
        OrderTicketService tickets = new OrderTicketService(mock(DiscordBotClient.class), props,
                mock(DiscordVerificationService.class));

        String coaching = tickets.compose(claim("PlayStation · ID VinayFC10 · Division 5 · Wants to work on: defending"));
        assertThat(coaching).contains("**Coaching:** PlayStation · ID VinayFC10 · Division 5");

        assertThat(tickets.compose(claim(null))).doesNotContain("Coaching:");
    }

    @Test
    @DisplayName("summarises a coaching order on one line, and says nothing for any other service")
    void summaryIsCoachingOnly() {
        OrderEntity coaching = mock(OrderEntity.class);
        when(coaching.getSku()).thenReturn(Sku.COACHING);
        when(coaching.getCoachingPlatform()).thenReturn(Platform.PLAYSTATION);
        when(coaching.getEaPlatformHandle()).thenReturn("VinayFC10");
        when(coaching.getCoachingRank()).thenReturn("Division 5");
        when(coaching.getCoachingFocus()).thenReturn("defending");

        assertThat(OrderService.coachingSummary(coaching))
                .contains(Platform.PLAYSTATION.displayName())
                .contains("ID VinayFC10")
                .contains("Division 5")
                .contains("defending");

        OrderEntity coins = mock(OrderEntity.class);
        when(coins.getSku()).thenReturn(Sku.TRADING_SERVICE);
        assertThat(OrderService.coachingSummary(coins)).isNull();
    }

    @Test
    @DisplayName("a coaching ticket still never carries an EA sign-in")
    void coachingTicketHasNoCredentials() {
        AppProperties props = mock(AppProperties.class);
        when(props.notifications()).thenReturn(mock(AppProperties.Notifications.class));
        String body = new OrderTicketService(mock(DiscordBotClient.class), props,
                mock(DiscordVerificationService.class))
                .compose(claim("PC · ID someone · Wants to work on: tactics"))
                .toLowerCase(Locale.ROOT);

        assertThat(body).doesNotContain("password").doesNotContain("backup code");
    }

    private static PaymentClaimNotification claim(String coachingDetails) {
        return new PaymentClaimNotification(
                "GFS-26-COACH01", "FUT Classes — Single session · 1 hour", "₹1,000.00",
                "UPI", "sharvinay088@okicici", "432198765012",
                "player@example.com", null, "Player One",
                false, true, Instant.parse("2026-09-14T10:00:00Z"),
                "https://globalfutservices.com/admin/orders/GFS-26-COACH01",
                coachingDetails);
    }
}
