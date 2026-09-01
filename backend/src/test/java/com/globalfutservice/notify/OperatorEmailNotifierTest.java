package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The operator email channel, which is the one thing standing between a paid order and
 * nobody knowing about it.
 *
 * <p>These tests exist because of a specific outage-shaped bug rather than for coverage.
 * For a long time this site took payments and told no one: the only mail it sent went to
 * the customer, and the alert it did have fired before the money moved. The two cases
 * below that assert <em>silence</em> are the important ones — the channel has two
 * independent switches, and having exactly one of them on is the failure that looks like
 * success. The `docker-compose.yml` shipped that way: `GFS_EMAIL_ENABLED=true` with no
 * recipient, which reads as configured and delivers nothing.
 */
class OperatorEmailNotifierTest {

    private static final OrderNotification ORDER = new OrderNotification(
            "GFS-26-BWG6NGG3",
            "READY_FOR_DELIVERY",
            "Safe Trading Service — 3M (PC)",
            "₹2,205.00",
            "customer@example.test",
            "PLAYER_AUCTION",
            "https://globalfutservices.com/admin/orders/GFS-26-BWG6NGG3");

    /** A notifications block with everything off except the bits a test names. */
    private static AppProperties.Notifications notifications(boolean emailEnabled, String recipients) {
        return new AppProperties.Notifications(
                false, null, null, null, "gfs_new_order", "https://graph.facebook.com/v20.0",
                false, null, null, "https://api.telegram.org",
                emailEnabled, recipients, "orders@globalfutservices.com", "Global FUT Services");
    }

    private static AppProperties propsWith(AppProperties.Notifications n) {
        AppProperties props = mock(AppProperties.class);
        when(props.notifications()).thenReturn(n);
        return props;
    }

    /* ------------------------------------------------------------ the switches --- */

    @Test
    @DisplayName("stays silent when the channel is switched off")
    void disabled_when_email_off() {
        JavaMailSender mail = mock(JavaMailSender.class);
        var notifier = new OperatorEmailNotifier(propsWith(notifications(false, "vinu@example.test")), mail);

        assertThat(notifier.isEnabled()).isFalse();
        notifier.readyToFulfil(ORDER);
        verify(mail, never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("stays silent when enabled but nobody is listed — the compose trap")
    void disabled_when_no_recipients() {
        JavaMailSender mail = mock(JavaMailSender.class);
        var notifier = new OperatorEmailNotifier(propsWith(notifications(true, "  ")), mail);

        assertThat(notifier.isEnabled()).isFalse();
        notifier.readyToFulfil(ORDER);
        verify(mail, never()).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));
    }

    @Test
    @DisplayName("is live once both switches are set")
    void enabled_when_both_present() {
        var notifier = new OperatorEmailNotifier(
                propsWith(notifications(true, "vinu@example.test")), mock(JavaMailSender.class));
        assertThat(notifier.isEnabled()).isTrue();
    }

    /* -------------------------------------------------------------- the alert --- */

    @Test
    @DisplayName("the paid alert carries [ACTION], the reference, the amount and a way in")
    void ready_to_fulfil_is_actionable() {
        JavaMailSender mail = mock(JavaMailSender.class);
        var notifier = new OperatorEmailNotifier(
                propsWith(notifications(true, "vinu@example.test, ops@example.test")), mail);

        notifier.readyToFulfil(ORDER);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        SimpleMailMessage msg = sent.getValue();

        // Comma-separated recipients are split and trimmed, so a stray space in the
        // environment variable cannot silently produce an unroutable address.
        assertThat(msg.getTo()).containsExactly("vinu@example.test", "ops@example.test");
        assertThat(msg.getFrom()).isEqualTo("orders@globalfutservices.com");

        // The prefix is what a phone filter keys on, so it is worth pinning.
        assertThat(msg.getSubject())
                .startsWith("[ACTION] ")
                .contains("GFS-26-BWG6NGG3")
                .contains("paid");

        assertThat(msg.getText())
                .contains("Safe Trading Service — 3M (PC)")
                .contains("₹2,205.00")
                .contains("Transfer market")
                .contains("https://globalfutservices.com/admin/orders/GFS-26-BWG6NGG3");
    }

    @Test
    @DisplayName("an unpaid order is recorded but not marked as a task")
    void order_placed_is_not_prefixed() {
        JavaMailSender mail = mock(JavaMailSender.class);
        var notifier = new OperatorEmailNotifier(
                propsWith(notifications(true, "vinu@example.test")), mail);

        notifier.orderPlaced(ORDER);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getSubject())
                .doesNotStartWith("[ACTION]")
                .doesNotStartWith("[WAITING]")
                .contains("awaiting payment");
    }

    @Test
    @DisplayName("a comfort trade blocked on the customer is [WAITING], not [ACTION]")
    void credentials_needed_is_waiting() {
        JavaMailSender mail = mock(JavaMailSender.class);
        var notifier = new OperatorEmailNotifier(
                propsWith(notifications(true, "vinu@example.test")), mail);

        notifier.credentialsNeeded(ORDER);

        ArgumentCaptor<SimpleMailMessage> sent = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(mail).send(sent.capture());
        assertThat(sent.getValue().getSubject()).startsWith("[WAITING] ");
    }

    /* ------------------------------------------------------------- resilience --- */

    @Test
    @DisplayName("a dead mail server never propagates into the payment transaction")
    void send_failure_is_swallowed() {
        JavaMailSender mail = mock(JavaMailSender.class);
        org.mockito.Mockito.doThrow(new org.springframework.mail.MailSendException("smtp down"))
                .when(mail).send(org.mockito.ArgumentMatchers.any(SimpleMailMessage.class));

        var notifier = new OperatorEmailNotifier(
                propsWith(notifications(true, "vinu@example.test")), mail);

        // The assertion is that this returns at all. An order that has been paid for must
        // not roll back because an SMTP host was unreachable.
        notifier.readyToFulfil(ORDER);
    }
}
