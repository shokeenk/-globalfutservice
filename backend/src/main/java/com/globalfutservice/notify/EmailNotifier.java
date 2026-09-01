package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Customer email.
 *
 * <p>The delivery message matters more than it looks. In the published Terms, receipt of
 * the "Order Delivered" email is the event that closes the refund window — so it is a
 * contractual notice, not a courtesy, and it is sent in the same transaction as the
 * status change rather than best-effort afterwards.
 */
@Component
public class EmailNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(EmailNotifier.class);

    private final AppProperties props;
    private final JavaMailSender mailSender;

    public EmailNotifier(AppProperties props, JavaMailSender mailSender) {
        this.props = props;
        this.mailSender = mailSender;
    }

    @Override
    public boolean isEnabled() {
        return props.notifications().emailEnabled();
    }

    @Override
    public String channelName() {
        return "email";
    }

    @Override
    public void orderPlaced(OrderNotification n) {
        send(n, "Order " + n.publicRef() + " received", """
                Thanks — we have your order.

                Reference: %s
                Service:   %s
                Total:     %s

                Track it any time at %s/track

                We aim to deliver well inside our published window. You will get another
                email the moment it is done.

                — Global FUT Services
                """.formatted(n.publicRef(), n.serviceLabel(), n.amountFormatted(), publicUrl()));
    }

    @Override
    public void credentialsNeeded(OrderNotification n) {
        send(n, "Action needed on order " + n.publicRef(), """
                Your order is paid and queued. To start, we need a few details from you.

                Reference: %s

                Add them here: %s/track

                Before you do, please make sure your account is signed out everywhere —
                console, web app and companion app — your transfer market is unlocked, and
                you have fewer than five unassigned items. Those four things account for
                almost every delayed order.

                — Global FUT Services
                """.formatted(n.publicRef(), publicUrl()));
    }

    @Override
    public void orderDelivered(OrderNotification n) {
        send(n, "Order " + n.publicRef() + " delivered", """
                Your order is complete.

                Reference: %s
                Service:   %s
                Total:     %s

                Two things worth doing now:

                  1. Change your EA password and regenerate your backup codes. We have
                     already deleted everything you gave us, and rotating is good hygiene
                     regardless.
                  2. Keep this email. Our seven-day guarantee runs from today — if
                     anything happens to the account in that window, reply to this message.

                Please note that under our Terms, receipt of this email closes the refund
                window for this order.

                — Global FUT Services
                """.formatted(n.publicRef(), n.serviceLabel(), n.amountFormatted()));
    }

    private String publicUrl() {
        return props.publicUrl();
    }

    /**
     * Tomorrow's session.
     *
     * <p>Rendered in the zone the customer booked in, and the zone is named in the message.
     * A reminder that says "19:00" without saying whose 19:00 is worse than no reminder:
     * the recipient has no way to tell whether we converted it for them or not, and the
     * failure mode is somebody missing a session they were trying to attend.
     */
    @Override
    public void coachingReminder(CoachingNotification n) {
        if (!isEnabled()) {
            log.debug("Email disabled; would have reminded {} about {}",
                    n.customerEmail(), n.sessionRef());
            return;
        }
        if (n.customerEmail() == null || n.customerEmail().isBlank()) {
            log.warn("No email address for session {}", n.sessionRef());
            return;
        }

        ZoneId zone = resolveZone(n.customerTimezone());
        String when = DateTimeFormatter
                .ofPattern("EEEE d MMMM 'at' HH:mm")
                .withLocale(Locale.UK)
                .format(n.startsAt().atZone(zone));

        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(props.notifications().emailFrom());
            message.setTo(n.customerEmail());
            message.setSubject("Your coaching session is tomorrow");
            message.setText("""
                    Your session is coming up.

                    When:      %s (%s)
                    Reference: %s

                    Manage it here: %s/coaching

                    If you need to move it, do that at least a few hours ahead and the
                    session goes straight back to your balance.

                    — Global FUT Services
                    """.formatted(when, zone.getId(), n.sessionRef(), publicUrl()));
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("Reminder for session {} failed: {}", n.sessionRef(), e.getMessage());
        }
    }

    /** Falls back to the business zone rather than to UTC, which nobody lives in. */
    private ZoneId resolveZone(String timezone) {
        if (timezone != null && !timezone.isBlank()) {
            try {
                return ZoneId.of(timezone);
            } catch (RuntimeException ignored) {
                // Fall through to the configured default.
            }
        }
        return props.loyalty().bonusZone();
    }

    private void send(OrderNotification n, String subject, String body) {
        if (!isEnabled()) {
            log.debug("Email disabled; would have sent '{}' to {}", subject, n.customerEmail());
            return;
        }
        if (n.customerEmail() == null || n.customerEmail().isBlank()) {
            log.warn("No email address on order {}", n.publicRef());
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(props.notifications().emailFrom());
            message.setTo(n.customerEmail());
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception e) {
            log.warn("Email to order {} failed: {}", n.publicRef(), e.getMessage());
        }
    }
}
