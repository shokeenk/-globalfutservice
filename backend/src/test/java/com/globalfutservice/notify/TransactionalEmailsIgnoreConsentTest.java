package com.globalfutservice.notify;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Order, payment, password and security emails never depend on marketing consent.
 *
 * <p>Two ways round. The order emails go to the order's address with nothing else asked --
 * here, for a customer who unsubscribed from everything. And no code outside marketing reads
 * the consent at all, so a later change cannot quietly start gating an order email on it.
 * (The account emails, for a customer who opted out, are in SignupSecurityPostgresTest.)
 */
class TransactionalEmailsIgnoreConsentTest {

    private static final String OPTED_OUT = "unsubscribed.from.offers@example.test";

    private static EmailNotifier notifier(JavaMailSender sender) {
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.notifications().emailEnabled()).thenReturn(true);
        when(props.notifications().emailFrom()).thenReturn("orders@globalfutservices.com");
        when(props.notifications().emailFromName()).thenReturn("Global FUT Services");
        when(props.publicUrl()).thenReturn("https://shop.example.test");
        when(props.discordInvite()).thenReturn("https://discord.gg/x");
        when(props.instagramUrl()).thenReturn("https://www.instagram.com/global_fut_services/");
        return new EmailNotifier(props, sender);
    }

    private static OrderNotification order() {
        return new OrderNotification("GFS-26-OPTOUT01", "READY_FOR_DELIVERY", "Buy Coins — 500K (PlayStation)",
                "₹8,250.00", OPTED_OUT, null, "COMFORT_TRADE", "TRADING_SERVICE", "PlayStation",
                "https://shop.example.test/admin/orders/GFS-26-OPTOUT01", null, null, null);
    }

    @Test
    @DisplayName("payment and order emails reach a customer who opted out of marketing")
    void orderEmailsSent() {
        JavaMailSender sender = SentEmail.sender();
        notifier(sender).orderConfirmed(order());
        assertThat(SentEmail.captured(sender).to()).contains(OPTED_OUT);

        JavaMailSender second = SentEmail.sender();
        notifier(second).awaitingVerification(order());
        assertThat(SentEmail.captured(second).to()).contains(OPTED_OUT);
    }

    @Test
    @DisplayName("nothing outside marketing and sign-up reads marketing consent")
    void onlyMarketingReadsConsent() throws IOException {
        Path root = Path.of("src/main/java/com/globalfutservice");
        List<String> readers;
        try (Stream<Path> files = Files.walk(root)) {
            readers = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("isMarketingOptIn()");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(p -> root.relativize(p).toString().replace('\\', '/'))
                    .sorted().toList();
        }
        // The campaign sender's audience query, the preference endpoints and the consent
        // record itself. No notifier, no order, payment or password code.
        assertThat(readers).allMatch(p -> p.startsWith("marketing/") || p.equals("identity/AccountEntity.java")
                || p.equals("identity/web/AccountController.java"));
        assertThat(readers).noneMatch(p -> p.startsWith("notify/") || p.startsWith("orders/")
                || p.startsWith("payments/") || p.startsWith("identity/Password")
                || p.startsWith("identity/EmailVerification"));
    }
}
