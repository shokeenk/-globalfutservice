package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The sign-in reminder reaches the customer, and only the customer.
 *
 * <p>The first request also alerts the operator channels. A reminder is sent by an
 * operator, so telling Discord, Telegram and WhatsApp about it would be telling staff what
 * one of them just did.
 */
class CredentialReminderEmailTest {

    private static OrderNotification order() {
        return new OrderNotification(
                "GFS-26-REMIND01", "CREDENTIALS_PENDING", "Buy Coins — 500K (PlayStation)", "₹8,250.00",
                "player@example.test", null, "COMFORT_TRADE", "TRADING_SERVICE", "PlayStation",
                "https://globalfutservices.com/admin/orders/GFS-26-REMIND01", null, null, null);
    }

    private static EmailNotifier notifier(JavaMailSender sender) {
        AppProperties props = mock(AppProperties.class);
        AppProperties.Notifications notifications = mock(AppProperties.Notifications.class);
        when(props.notifications()).thenReturn(notifications);
        when(notifications.emailEnabled()).thenReturn(true);
        when(notifications.emailFrom()).thenReturn("orders@globalfutservices.com");
        when(notifications.emailFromName()).thenReturn("Global FUT Services");
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(props.instagramUrl()).thenReturn("https://www.instagram.com/global_fut_services/");
        when(props.discordInvite()).thenReturn("https://discord.gg/8FeP7C6tXt");
        return new EmailNotifier(props, sender);
    }

    @Test
    @DisplayName("says it is a reminder, and asks for the same thing the first email did")
    void customerEmail() {
        JavaMailSender sender = SentEmail.sender();

        notifier(sender).credentialsReminder(order());

        // A coin order: the branded email, with the order's own tracking page in both parts.
        SentEmail mail = SentEmail.captured(sender);
        assertThat(mail.subject()).isEqualTo("Reminder: action needed on order GFS-26-REMIND01");
        assertThat(mail.to()).isEqualTo("player@example.test");
        assertThat(mail.text())
                .contains("Reference: GFS-26-REMIND01")
                .contains("Add them here: https://globalfutservices.com/track?ref=GFS-26-REMIND01");
        assertThat(mail.html())
                .contains("TRACK YOUR ORDER")
                .contains("https://globalfutservices.com/track?ref=GFS-26-REMIND01");
    }

    @Test
    @DisplayName("the operator channels keep the interface's silent default")
    void operatorChannelsStayQuiet() {
        for (Class<?> channel : new Class<?>[]{DiscordNotifier.class, OperatorEmailNotifier.class,
                TelegramNotifier.class, WhatsAppNotifier.class}) {
            assertThatThrownBy(() -> channel.getDeclaredMethod("credentialsReminder", OrderNotification.class))
                    .as(channel.getSimpleName())
                    .isInstanceOf(NoSuchMethodException.class);
        }
    }
}
