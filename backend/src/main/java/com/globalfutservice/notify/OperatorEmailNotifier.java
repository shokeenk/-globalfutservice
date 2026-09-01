package com.globalfutservice.notify;

import com.globalfutservice.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/**
 * Order alerts for whoever is on shift.
 *
 * <p><b>Why this is separate from {@link EmailNotifier}.</b> That class writes to
 * {@code n.customerEmail()} and nothing else — it is the customer's receipt channel.
 * There was no operator channel at all, which is why orders were arriving and nobody
 * was being told. This is the missing half: the same events, addressed to the people
 * who have to act on them.
 *
 * <p><b>Why email is worth having even once WhatsApp is live.</b> WhatsApp needs Meta
 * business verification, a dedicated sender number and an approved template before it
 * can deliver a single message. Email needs an SMTP host. When an order is sitting
 * unfulfilled, "works today" beats "works better in a fortnight" — and an inbox is
 * also a searchable record, which a phone notification is not.
 *
 * <p>Unlike the phone channels, this one <b>does</b> report unpaid orders. Alert
 * fatigue is a property of the medium: a buzz for an abandoned cart trains someone to
 * ignore their phone, whereas a message in a mailbox costs nothing to leave unread.
 * The subject line carries the distinction so the inbox stays sortable — anything
 * needing action is prefixed, anything informational is not.
 *
 * <p>Recipients are a list. A one-person business puts one address in; the moment
 * there are two people on shift, nobody has to remember to set up forwarding.
 */
@Component
public class OperatorEmailNotifier implements Notifier {

    private static final Logger log = LoggerFactory.getLogger(OperatorEmailNotifier.class);

    private final AppProperties props;
    private final JavaMailSender mailSender;

    public OperatorEmailNotifier(AppProperties props, JavaMailSender mailSender) {
        this.props = props;
        this.mailSender = mailSender;
    }

    @Override
    public boolean isEnabled() {
        AppProperties.Notifications n = props.notifications();
        return n.emailEnabled() && !recipients().isEmpty();
    }

    @Override
    public String channelName() {
        return "operator-email";
    }

    /* ------------------------------------------------------------- events --- */

    /** Money has settled and the order is in the queue. The one that needs action. */
    @Override
    public void readyToFulfil(OrderNotification n) {
        send(n, "[ACTION] " + n.publicRef() + " paid — ready to fulfil",
                """
                This order has been paid for and is waiting in the queue.

                %s
                Amount:   %s
                Method:   %s

                Open it here:
                %s
                """.formatted(n.serviceLabel(), n.amountFormatted(),
                        readableMethod(n.deliveryMethod()), n.adminDeepLink()));
    }

    /** Blocked on the customer, but worth knowing about — it may need chasing. */
    @Override
    public void credentialsNeeded(OrderNotification n) {
        send(n, "[WAITING] " + n.publicRef() + " needs the customer's sign-in",
                """
                Paid, but it is a comfort trade and the customer has not submitted their
                sign-in yet. Nothing to do until they do; chase it if it sits here.

                %s
                Amount:   %s

                Open it here:
                %s
                """.formatted(n.serviceLabel(), n.amountFormatted(), n.adminDeepLink()));
    }

    /**
     * An order was started but not yet paid.
     *
     * <p>Informational, and unprefixed on purpose. Most of these are abandoned before
     * they are paid, so this is a record rather than a task — it exists so that a
     * morning's volume is visible without anyone opening the console.
     */
    @Override
    public void orderPlaced(OrderNotification n) {
        send(n, n.publicRef() + " placed — awaiting payment",
                """
                A customer has started this order. Nothing to do yet; if it is paid you
                will get a second mail marked [ACTION].

                %s
                Amount:   %s

                Open it here:
                %s
                """.formatted(n.serviceLabel(), n.amountFormatted(), n.adminDeepLink()));
    }

    /** Delivered. Closes the loop for whoever was not the one who delivered it. */
    @Override
    public void orderDelivered(OrderNotification n) {
        send(n, n.publicRef() + " delivered",
                """
                Marked delivered. The guarantee window has started.

                %s
                Amount:   %s

                Open it here:
                %s
                """.formatted(n.serviceLabel(), n.amountFormatted(), n.adminDeepLink()));
    }

    /* -------------------------------------------------------------- send --- */

    private java.util.List<String> recipients() {
        String raw = props.notifications().operatorEmails();
        if (raw == null || raw.isBlank()) return java.util.List.of();
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void send(OrderNotification n, String subject, String body) {
        if (!isEnabled()) {
            log.debug("Operator email disabled; would have sent '{}'", subject);
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(props.notifications().emailFrom());
            message.setTo(recipients().toArray(String[]::new));
            message.setSubject(subject);
            message.setText(body);
            mailSender.send(message);
        } catch (Exception e) {
            // Swallowed, like every other channel. A paid order must never roll back
            // because a mail server was unreachable.
            log.warn("Operator email for {} failed: {}", n.publicRef(), e.getMessage());
        }
    }

    private static String readableMethod(String method) {
        if (method == null) return "-";
        return "COMFORT_TRADE".equals(method) ? "Comfort trade" : "Transfer market";
    }
}
