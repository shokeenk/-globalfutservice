package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Where the order-confirmed email's "Message us on Discord" button goes. */
class EmailDiscordLinkTest {

    private static EmailNotifier notifier(String adminId) {
        AppProperties props = mock(AppProperties.class);
        AppProperties.Notifications notifications = mock(AppProperties.Notifications.class);
        when(props.notifications()).thenReturn(notifications);
        when(notifications.discordAdminId()).thenReturn(adminId);
        when(props.discordInvite()).thenReturn("https://discord.com/invite/8FeP7C6tXt");
        return new EmailNotifier(props, mock(JavaMailSender.class));
    }

    @Test
    @DisplayName("a direct message to the account in GFS_DISCORD_ADMIN_ID, the one the storefront's links open")
    void directMessage() {
        assertThat(notifier("1300551868174569595").discordDmUrl())
                .isEqualTo("https://discord.com/users/1300551868174569595");
        assertThat(notifier(" 1300551868174569595 ").discordDmUrl())
                .isEqualTo("https://discord.com/users/1300551868174569595");
    }

    @Test
    @DisplayName("without a usable id there is no link that opens a DM, so the server invite stands in")
    void fallback() {
        assertThat(notifier(null).discordDmUrl()).isEqualTo("https://discord.com/invite/8FeP7C6tXt");
        assertThat(notifier("").discordDmUrl()).isEqualTo("https://discord.com/invite/8FeP7C6tXt");
        assertThat(notifier("globalfutservices").discordDmUrl()).isEqualTo("https://discord.com/invite/8FeP7C6tXt");
    }
}
