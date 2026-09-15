package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
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

    @Test
    @DisplayName("the confirmation email carries the Discord invite and the order reference")
    void confirmationEmailCarriesInvite() {
        JavaMailSender sender = mock(JavaMailSender.class);
        AppProperties props = mock(AppProperties.class);
        AppProperties.Notifications notifications = mock(AppProperties.Notifications.class);
        when(props.notifications()).thenReturn(notifications);
        when(notifications.emailEnabled()).thenReturn(true);
        when(notifications.emailFrom()).thenReturn("orders@globalfutservices.com");
        when(props.discordInvite()).thenReturn(INVITE);

        new EmailNotifier(props, sender).coachingConfirmed(new OrderNotification(
                "GFS-26-COACH01", "PAID", "FUT Classes — Single session · 1 hour", "₹1,000.00",
                "player@example.com", null, "SCHEDULED_SESSION",
                "https://globalfutservices.com/admin/orders/GFS-26-COACH01",
                "PlayStation · ID VinayFC10"));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("player@example.com");
        assertThat(sent.getValue().getText()).contains(INVITE).contains("GFS-26-COACH01");
    }

    @Test
    @DisplayName("the payment ticket shows the coaching details when there are some, and nothing otherwise")
    void ticketCarriesCoachingDetails() {
        AppProperties props = mock(AppProperties.class);
        when(props.notifications()).thenReturn(mock(AppProperties.Notifications.class));
        OrderTicketService tickets = new OrderTicketService(mock(DiscordBotClient.class), props);

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
        String body = new OrderTicketService(mock(DiscordBotClient.class), props)
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
