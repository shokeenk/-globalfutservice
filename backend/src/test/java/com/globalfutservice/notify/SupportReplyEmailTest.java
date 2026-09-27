package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A support reply reaches the customer in full, with the way back into the conversation. */
class SupportReplyEmailTest {

    private static EmailNotifier notifier(JavaMailSender sender) {
        AppProperties props = mock(AppProperties.class);
        AppProperties.Notifications notifications = mock(AppProperties.Notifications.class);
        when(props.notifications()).thenReturn(notifications);
        when(notifications.emailEnabled()).thenReturn(true);
        when(notifications.emailFrom()).thenReturn("orders@globalfutservices.com");
        when(notifications.emailFromName()).thenReturn("Global FUT Services");
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(props.instagramUrl()).thenReturn("https://www.instagram.com/global_fut_services/");
        return new EmailNotifier(props, sender);
    }

    @Test
    @DisplayName("the reply, the ticket reference and the private link, to the customer")
    void reply() {
        JavaMailSender sender = mock(JavaMailSender.class);
        notifier(sender).supportReply(new SupportReplyNotification("rahul07@example.test", "TKT-AB12CD34",
                "Coins not received", "Your coins are on the way.",
                "https://globalfutservices.com/support/tickets/TKT-AB12CD34?key=k3y", false));

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(sent.capture());
        assertThat(sent.getValue().getTo()).containsExactly("rahul07@example.test");
        assertThat(sent.getValue().getSubject()).isEqualTo("Re: Coins not received [TKT-AB12CD34]");
        assertThat(sent.getValue().getText())
                .contains("Your coins are on the way.")
                .contains("https://globalfutservices.com/support/tickets/TKT-AB12CD34?key=k3y")
                .contains("answers sent to this email address do not reach us");
    }

    @Test
    @DisplayName("the operator channels do not repeat a reply back to staff")
    void operatorChannelsQuiet() {
        for (Class<?> channel : new Class<?>[]{DiscordNotifier.class, OperatorEmailNotifier.class,
                TelegramNotifier.class, WhatsAppNotifier.class}) {
            assertThatThrownBy(() -> channel.getDeclaredMethod("supportReply", SupportReplyNotification.class))
                    .as(channel.getSimpleName()).isInstanceOf(NoSuchMethodException.class);
        }
    }
}
