package com.globalfutservice.notify;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
 * A customer whose order is on hold is told what to do by email and in their order's
 * ticket, and nowhere else: the operator channels hear about it as a fulfilment alert.
 */
class CustomerActionNoticeTest {

    private static final String REF = "GFS-26-ACTION01";
    private static final String INSTRUCTION = "Your backup codes have been used or were not accepted. Please create "
            + "new ones in your EA account and enter them on your order page.";

    private static CustomerActionNotification notice() {
        return new CustomerActionNotification(new OrderNotification(
                REF, "ON_HOLD", "Buy Coins — 500K (PlayStation)", "₹8,250.00",
                "player@example.test", "player#1", "PLAYER_AUCTION", "TRADING_SERVICE", "PlayStation",
                "https://globalfutservices.com/admin/orders/" + REF, null, null, null), INSTRUCTION);
    }

    @Nested
    class Email {

        @Test
        @DisplayName("says what to do, where the order page is, and that we never ask for a password by email")
        void email() {
            AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
            when(props.notifications().emailEnabled()).thenReturn(true);
            when(props.notifications().emailFrom()).thenReturn("orders@globalfutservices.com");
            when(props.publicUrl()).thenReturn("https://globalfutservices.com");
            JavaMailSender sender = mock(JavaMailSender.class);

            new EmailNotifier(props, sender).customerActionNeeded(notice());

            ArgumentCaptor<SimpleMailMessage> message = ArgumentCaptor.forClass(SimpleMailMessage.class);
            verify(sender).send(message.capture());
            assertThat(message.getValue().getTo()).containsExactly("player@example.test");
            assertThat(message.getValue().getSubject()).isEqualTo("Action needed on order " + REF);
            assertThat(message.getValue().getText())
                    .contains("Reference: " + REF)
                    .contains(INSTRUCTION)
                    .contains("https://globalfutservices.com/track?ref=" + REF)
                    .contains("https://globalfutservices.com/support")
                    .contains("never ask for your password or backup codes by email");
        }
    }

    @Nested
    class Ticket {

        private final DiscordBotClient bot = mock(DiscordBotClient.class);
        private final DiscordNotifier notifier;

        Ticket() {
            AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
            when(props.publicUrl()).thenReturn("https://globalfutservices.com");
            when(props.notifications().discordAdminId()).thenReturn("123456789");
            when(bot.isEnabled()).thenReturn(true);
            notifier = new DiscordNotifier(props, new ObjectMapper(), null, bot);
            notifier.retryDelays = List.of(Duration.ZERO, Duration.ZERO);
        }

        @Test
        @DisplayName("posts the customer's sentence in their ticket, and mentions nobody")
        void posted() {
            when(bot.findTicketChannel(REF)).thenReturn(Optional.of("ticket-ch"));

            notifier.customerActionNeeded(notice());

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(bot).postMessage(eq("ticket-ch"), text.capture());
            assertThat(text.getValue())
                    .contains(REF)
                    .contains(INSTRUCTION)
                    .contains("https://globalfutservices.com/track?ref=" + REF)
                    .doesNotContain("<@")
                    .doesNotContain("admin");
            verify(bot, never()).postEmbed(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("an order without a ticket gets the email only")
        void noTicket() {
            when(bot.findTicketChannel(REF)).thenReturn(Optional.empty());

            notifier.customerActionNeeded(notice());

            verify(bot, never()).postMessage(anyString(), anyString());
        }

        @Test
        @DisplayName("Discord failing is retried while it could work, and never thrown")
        void failureSwallowed() {
            when(bot.findTicketChannel(REF)).thenReturn(Optional.of("ticket-ch"));
            doThrow(new DiscordBotClient.DiscordException("unavailable", 503))
                    .when(bot).postMessage(eq("ticket-ch"), anyString());

            notifier.customerActionNeeded(notice());

            verify(bot, times(3)).postMessage(eq("ticket-ch"), anyString());
        }

        @Test
        @DisplayName("finding the ticket failing is logged, not thrown")
        void lookupFailure() {
            when(bot.findTicketChannel(REF)).thenThrow(new RuntimeException("Discord down"));

            notifier.customerActionNeeded(notice());

            verify(bot, never()).postMessage(anyString(), anyString());
        }
    }

    @Test
    @DisplayName("the operator-only channels keep the interface's silent default")
    void operatorChannelsStayQuiet() {
        for (Class<?> channel : new Class<?>[]{OperatorEmailNotifier.class, TelegramNotifier.class,
                WhatsAppNotifier.class}) {
            assertThatThrownBy(() -> channel.getDeclaredMethod("customerActionNeeded", CustomerActionNotification.class))
                    .as(channel.getSimpleName())
                    .isInstanceOf(NoSuchMethodException.class);
        }
    }
}
