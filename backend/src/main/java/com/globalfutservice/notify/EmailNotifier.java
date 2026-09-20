package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.notify.email.EmailTemplate;
import com.globalfutservice.notify.email.TransactionalEmails;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

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

    /**
     * Deliberately silent.
     *
     * <p>Placing an order is no longer an email. The mailing specification consolidates
     * the customer's payment-status mail down to two messages — awaiting verification and
     * confirmed — and an "order received" note sent before either of them arrives is the
     * third. The event itself still fans out to the operator channels, which do want to
     * know an order exists before any money moves.
     */
    @Override
    public void orderPlaced(OrderNotification n) {
        log.debug("orderPlaced is not emailed to customers; {} gets awaitingVerification instead",
                n.publicRef());
    }

    /**
     * Email 1 — the customer has submitted a payment reference and screenshot.
     */
    @Override
    public void awaitingVerification(OrderNotification n) {
        var rendered = TransactionalEmails.awaitingVerification(n, brand(), trackUrl(n));
        sendHtml(n, rendered);
    }

    /**
     * Email 2 — an operator verified the payment, or the gateway captured it.
     */
    @Override
    public void orderConfirmed(OrderNotification n) {
        var rendered = TransactionalEmails.orderConfirmed(
                n, brand(), trackUrl(n), props.discordInvite());
        sendHtml(n, rendered);
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

    /**
     * Deliberately silent, for the same reason as {@link #orderPlaced}.
     *
     * <p>Coaching used to get its own "join us on Discord" mail at confirmation. The
     * confirmation email now carries that: its Discord branch covers Champs, boosting and
     * coaching alike, with the three-step block the reference design specifies. Leaving
     * this one sending too would put two confirmations in a coaching customer's inbox
     * within a second of each other. The event still fans out to the other channels.
     */
    @Override
    public void coachingConfirmed(OrderNotification n) {
        log.debug("coachingConfirmed is not emailed separately; {} is covered by orderConfirmed",
                n.publicRef());
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

    /** Footer destinations, resolved from configuration rather than written into the copy. */
    private EmailTemplate.Brand brand() {
        String website = props.publicUrl();
        return new EmailTemplate.Brand(
                website, hostOf(website),
                props.discordInvite(), "Join the GFS Discord",
                props.instagramUrl(), "@" + handleOf(props.instagramUrl()));
    }

    /**
     * The customer's own order, not the generic lookup form.
     *
     * <p>{@code /track?ref=...} is what the in-app notification feed already links to, so
     * this is the same destination by the same route rather than a second tracking
     * mechanism. The page still asks for the email on the order before it shows anything.
     */
    private String trackUrl(OrderNotification n) {
        return publicUrl() + "/track?ref=" + n.publicRef();
    }

    private static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url).getHost();
            return host == null ? url : host.replaceFirst("^www\\.", "");
        } catch (RuntimeException e) {
            return url;
        }
    }

    private static String handleOf(String instagramUrl) {
        String trimmed = instagramUrl.replaceAll("/+$", "");
        return trimmed.substring(trimmed.lastIndexOf('/') + 1);
    }

    /**
     * Sends the branded message, with a plain-text part alongside it.
     *
     * <p>Multipart rather than HTML alone: a text/plain alternative is what a screen
     * reader, a watch and a spam filter each read first, and an HTML-only message scores
     * worse on delivery for no benefit. Both parts carry the same facts.
     */
    private void sendHtml(OrderNotification n, TransactionalEmails.Rendered rendered) {
        if (!isEnabled()) {
            log.debug("Email disabled; would have sent '{}' to {}",
                    rendered.subject(), n.customerEmail());
            return;
        }
        if (n.customerEmail() == null || n.customerEmail().isBlank()) {
            log.warn("No email address on order {}", n.publicRef());
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, MimeMessageHelper.MULTIPART_MODE_MIXED_RELATED,
                    StandardCharsets.UTF_8.name());
            helper.setFrom(props.notifications().emailFrom(),
                    props.notifications().emailFromName());
            helper.setTo(n.customerEmail());
            helper.setSubject(rendered.subject());
            helper.setText(rendered.text(), rendered.html());
            mailSender.send(message);
            log.info("Sent '{}' for order {}", rendered.subject(), n.publicRef());
        } catch (UnsupportedEncodingException e) {
            // The from-name is configuration, so this is a deployment error, not a per-order one.
            log.error("Sender name is not encodable: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Email to order {} failed: {}", n.publicRef(), e.getMessage());
        }
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
