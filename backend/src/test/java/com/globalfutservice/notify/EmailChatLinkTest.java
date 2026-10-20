package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Where an email's "Chat with us" goes: the order's live chat, for owners and guests alike -- never Discord. */
class EmailChatLinkTest {

    private static EmailNotifier notifier() {
        AppProperties props = mock(AppProperties.class);
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(props.discordInvite()).thenReturn("https://discord.com/invite/8FeP7C6tXt");
        return new EmailNotifier(props, mock(JavaMailSender.class));
    }

    private static OrderNotification order(String ref) {
        return new OrderNotification(ref, "PAID", "Champs Boosting", "₹2,100.00", "buyer@example.test", null,
                "COMFORT_TRADE", "BOOST_CHAMPS", "PlayStation", null, null, null, null);
    }

    @Test
    @DisplayName("the order's live chat page, which works signed in or not, with the reference in it")
    void chat() {
        assertThat(notifier().supportUrl(order("GFS-26-AB12CD34")))
                .isEqualTo("https://globalfutservices.com/support/chat?ref=GFS-26-AB12CD34");
        assertThat(notifier().supportUrl(order("GFS-26-AB12CD34"))).doesNotContain("discord");
    }

    @Test
    @DisplayName("a reference is put in the address as a query value, never as raw text")
    void encoded() {
        assertThat(notifier().supportUrl(order("GFS 26&x=1"))).endsWith("?ref=GFS%2026%26x%3D1");
    }
}
