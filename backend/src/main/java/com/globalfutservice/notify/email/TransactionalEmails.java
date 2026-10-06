package com.globalfutservice.notify.email;

import com.globalfutservice.notify.OrderNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * The emails the order lifecycle sends a customer.
 *
 * <p>Two payment-status emails, for every order: one when they tell us they have paid, one
 * when we confirm we found the money. The specification consolidates a longer list of
 * payment-status mail down to exactly these two.
 *
 * <p><b>What is deliberately NOT folded into these two.</b> The delivery email is a
 * contractual notice — the published Terms run the guarantee window from delivery, and
 * that email is how the customer learns the window has started — so it is left alone. So
 * is the credentials request, without which an order stalls in silence, and the notice
 * that an order is on hold until the customer acts. Consolidating the <i>payment-status</i>
 * emails is what this is; deleting the notice the Terms rely on would be a different and
 * much worse change.
 *
 * <p><b>Every coin email carries the order's tracking link.</b> A coin order is fulfilled
 * without the customer, so the tracking page is where they follow it, at every step. The
 * credentials request, the on-hold notice and the delivery email are therefore also
 * rendered here for coin orders, branded, with the same TRACK YOUR ORDER button as the
 * two above and the same link in the plain text. Their words are the plain-text versions'
 * own; only the layout is new. Other orders still get those three as plain text.
 *
 * <p>All render into {@link EmailTemplate}, so the header, the footer links and the trust
 * badges are defined once and cannot drift apart.
 */
public final class TransactionalEmails {

    /** The Discord account customers message, as the storefront names it. */
    static final String DISCORD_NAME = "globalfutservices";

    /** Coins go to order tracking, coaching to the order's support page, the rest to Discord. */
    private static final String COINS_SKU = "TRADING_SERVICE";
    private static final String COACHING_SKU = "COACHING";

    /** The brief's coaching copy, word for word, as the storefront's card has it. */
    static final String COACH_TITLE = "Connect with Your Coach";
    static final String COACH_BODY = "Need help with your coaching order or session? Connect with your coach "
            + "and continue the conversation directly from your GFS account.";
    static final String COACH_BUTTON = "CONNECT WITH COACH";
    /** The support page is the owner's alone, so the link needs them signed in. */
    static final String COACH_SIGN_IN =
            "The page opens when you are signed in to the GFS account you placed this order from.";

    public record Rendered(String subject, String html, String text) {
    }

    private TransactionalEmails() {
    }

    /**
     * Sent when the customer submits their payment reference and screenshot.
     *
     * <p>Answers the question somebody asks three minutes after paying: did that land.
     * It deliberately does not say the payment is confirmed, because at this point nobody
     * has looked at it — the status card reads "Awaiting Verification" and the closing
     * line says a human will check it.
     */
    public static Rendered awaitingVerification(OrderNotification n, EmailTemplate.Brand brand,
                                                String trackUrl) {
        List<EmailTemplate.InfoCard> cards = new ArrayList<>();
        cards.add(EmailTemplate.InfoCard.of("◆", "Order Number", "#" + n.publicRef()));
        cards.add(EmailTemplate.InfoCard.of("●", "Service", n.serviceLabel()));
        cards.add(EmailTemplate.InfoCard.accented("▸", "Order Status", "Awaiting Verification"));

        EmailTemplate.Content content = new EmailTemplate.Content(
                "We have your payment details — our team is verifying them now.",
                // Not a tick: nothing has been verified yet, and a tick here would
                // read as confirmation to somebody skimming.
                "▸",
                null,
                "YOUR GFS ORDER IS AWAITING VERIFICATION",
                "Thank you for submitting your payment details. We have received your payment "
                        + "screenshot and UTR details successfully.",
                cards,
                EmailTemplate.paragraphs(
                        "Your payment details have been received. Our team will verify your "
                                + "payment and update your order once verification is complete."),
                null,
                List.of(),
                null,
                "TRACK YOUR ORDER",
                trackUrl,
                orderFooterNote());

        String text = """
                YOUR GFS ORDER IS AWAITING VERIFICATION

                Thank you for submitting your payment details. We have received your
                payment screenshot and UTR details successfully.

                Order #%s
                %s
                Status: Awaiting Verification

                Track your order: %s

                Your payment details have been received. Our team will verify your payment
                and update your order once verification is complete.

                — Global FUT Services
                """.formatted(n.publicRef(), n.serviceLabel(), trackUrl);

        return new Rendered("Your GFS Order Is Awaiting Verification",
                EmailTemplate.render(content, brand), text);
    }

    /**
     * The session a coaching order is booked into, in the customer's own time zone and
     * named, e.g. "Mon 5 Oct 2026, 7:00 PM (Europe/London)". Null when there is none.
     * An unreadable zone falls back to India's, where the business runs.
     */
    static String sessionTime(OrderNotification n) {
        if (n.sessionStartsAt() == null) {
            return null;
        }
        java.time.ZoneId zone;
        try {
            zone = n.sessionTimezone() == null || n.sessionTimezone().isBlank()
                    ? java.time.ZoneId.of("Asia/Kolkata") : java.time.ZoneId.of(n.sessionTimezone());
        } catch (RuntimeException e) {
            zone = java.time.ZoneId.of("Asia/Kolkata");
        }
        return java.time.format.DateTimeFormatter
                .ofPattern("EEE d MMM uuuu, h:mm a", java.util.Locale.ENGLISH)
                .format(n.sessionStartsAt().atZone(zone)) + " (" + zone.getId() + ")";
    }

    /**
     * Sent when an operator has verified the payment against the account.
     *
     * <p>Branches on the SKU, because what the customer does next genuinely differs.
     * A coin order is fulfilled without them: the useful next step is watching it. A
     * coaching order is arranged with the coach on the order's own support page, on this
     * site. Champs and boosting need a conversation before anyone can start, and that
     * conversation happens in a Discord ticket, where the EA login is handed over.
     *
     * @param coachUrl the order's Connect with Coach page; used for coaching orders only
     */
    public static Rendered orderConfirmed(OrderNotification n, EmailTemplate.Brand brand,
                                          String trackUrl, String discordDmUrl, String coachUrl) {
        boolean coins = COINS_SKU.equals(n.sku());
        boolean coaching = COACHING_SKU.equals(n.sku());
        boolean discord = !coins && !coaching;

        List<EmailTemplate.InfoCard> cards = new ArrayList<>();
        cards.add(EmailTemplate.InfoCard.of("◆", "Order Number", "#" + n.publicRef()));
        cards.add(EmailTemplate.InfoCard.of("●", "Service", n.serviceLabel()));
        cards.add(EmailTemplate.InfoCard.accented("✓", "Order Status", "Confirmed"));

        // Amount, and platform only where the SKU has one — printed empty it reads as a
        // missing value rather than an inapplicable one.
        cards.add(EmailTemplate.InfoCard.of("■", "Amount", n.amountFormatted()));
        if (n.platform() != null && !n.platform().isBlank()) {
            cards.add(EmailTemplate.InfoCard.of("▪", "Platform", n.platform()));
        }
        String session = sessionTime(n);
        if (session != null) {
            cards.add(EmailTemplate.InfoCard.accented("◷", "Your session", session));
        }

        // A direct message to our account, with the reference in it. The /verify command
        // this used to lead to is not offered to customers any more.
        List<EmailTemplate.Step> steps = !discord ? List.of() : List.of(
                new EmailTemplate.Step(
                        "Message us on Discord: " + DISCORD_NAME,
                        "Send a direct message to " + DISCORD_NAME + " on Discord.",
                        "MESSAGE US ON DISCORD", discordDmUrl),
                EmailTemplate.Step.of(
                        "Include your order reference: " + n.publicRef(),
                        "Put it in your message so we can find your order straight away."),
                EmailTemplate.Step.of(
                        "Our Operations Executive will reply",
                        "One of our operations executives will connect with you and guide you "
                                + "through the next steps."));

        EmailTemplate.Content content = new EmailTemplate.Content(
                "Your payment is verified and your order is confirmed.",
                "✓",
                null,
                "YOUR GFS ORDER IS CONFIRMED!",
                "Thank you for your order. Your order has been successfully received and is "
                        + "now being processed.",
                cards,
                coaching ? coachBlock() : null,
                discord ? "NEXT STEPS" : null,
                steps,
                coaching ? COACH_SIGN_IN : null,
                coins ? "TRACK YOUR ORDER" : coaching ? COACH_BUTTON : null,
                coins ? trackUrl : coaching ? coachUrl : null,
                orderFooterNote());

        String next = coins
                ? "Track your order: " + trackUrl
                : coaching
                ? """
                  CONNECT WITH YOUR COACH
                  %s

                  Connect with Coach: %s

                  %s""".formatted(COACH_BODY, coachUrl, COACH_SIGN_IN)
                : """
                  NEXT STEPS
                    1. Message us on Discord: %s
                       %s
                    2. Include your order reference: %s
                    3. Our Operations Executive will reply and guide you through the next steps."""
                  .formatted(DISCORD_NAME, discordDmUrl, n.publicRef());

        String text = """
                YOUR GFS ORDER IS CONFIRMED

                Thank you for your order. Your order has been successfully received and is
                now being processed.

                Order #%s
                %s
                Status: Confirmed
                Amount: %s%s%s

                %s

                — Global FUT Services
                """.formatted(n.publicRef(), n.serviceLabel(), n.amountFormatted(),
                n.platform() == null || n.platform().isBlank() ? "" : "\nPlatform: " + n.platform(),
                session == null ? "" : "\nYour session: " + session,
                next);

        return new Rendered("Your GFS Order Is Confirmed",
                EmailTemplate.render(content, brand), text);
    }

    /** Coin orders: every email about them carries the order's tracking link. */
    public static boolean isCoins(OrderNotification n) {
        return COINS_SKU.equals(n.sku());
    }

    // ------------------------------------------------- sign-in request and reminder

    private static final String SIGN_IN_INTRO =
            "Your order is paid and queued. To start, we need a few details from you.";
    private static final String SIGN_IN_BEFORE = "Before you do, please make sure your account is signed out "
            + "everywhere — console, web app and companion app — your transfer market is unlocked, and you have "
            + "fewer than five unassigned items. Those four things account for almost every delayed order.";

    /** The subject of the sign-in request, or of the reminder staff send from the Orders page. */
    public static String credentialsSubject(OrderNotification n, boolean reminder) {
        return (reminder ? "Reminder: action needed on order " : "Action needed on order ") + n.publicRef();
    }

    /**
     * The sign-in request as plain text. The reminder uses the same words, so the customer
     * is not left comparing two sets of instructions; only the subject says it is a reminder.
     *
     * @param addUrl where the details are added: the order's own tracking page
     */
    public static String credentialsText(OrderNotification n, String addUrl) {
        return """
                Your order is paid and queued. To start, we need a few details from you.

                Reference: %s

                Add them here: %s

                Before you do, please make sure your account is signed out everywhere —
                console, web app and companion app — your transfer market is unlocked, and
                you have fewer than five unassigned items. Those four things account for
                almost every delayed order.

                — Global FUT Services
                """.formatted(n.publicRef(), addUrl);
    }

    /** The sign-in request for a coin order: branded, with the button to the order's tracking page. */
    public static Rendered credentialsRequest(OrderNotification n, EmailTemplate.Brand brand, String trackUrl,
                                              boolean reminder) {
        List<EmailTemplate.InfoCard> cards = List.of(
                EmailTemplate.InfoCard.of("◆", "Order Number", "#" + n.publicRef()),
                EmailTemplate.InfoCard.of("●", "Service", n.serviceLabel()),
                EmailTemplate.InfoCard.accented("▸", "Order Status", "Queued"));
        EmailTemplate.Content content = new EmailTemplate.Content(
                SIGN_IN_INTRO,
                "▸",
                null,
                "ACTION NEEDED ON YOUR GFS ORDER",
                SIGN_IN_INTRO,
                cards,
                EmailTemplate.paragraphs("Add them on your order page, from the button below.", SIGN_IN_BEFORE),
                null,
                List.of(),
                null,
                "TRACK YOUR ORDER",
                trackUrl,
                orderFooterNote());
        return new Rendered(credentialsSubject(n, reminder), EmailTemplate.render(content, brand),
                credentialsText(n, trackUrl));
    }

    // ----------------------------------------------------------- order on hold

    private static final String HOLD_INTRO = "Your order is paused until you do one thing for us.";
    private static final String NO_PASSWORD_BY_EMAIL = "We will never ask for your password or backup codes by email.";

    /**
     * The order is on hold until the customer fixes something on their EA account, as plain
     * text. The instruction is the storefront's own sentence.
     */
    public static String actionNeededText(OrderNotification n, String instruction, String trackUrl,
                                          String publicUrl) {
        return """
                Your order is paused until you do one thing for us.

                Reference: %s

                %s

                Track your order: %s

                When it's done, or if you have a question, tell us in your order's Discord ticket or
                at %s/support. Replies to this email do not reach us.
                We will never ask for your password or backup codes by email.

                — Global FUT Services
                """.formatted(n.publicRef(), instruction, trackUrl, publicUrl);
    }

    /** The on-hold notice: branded, with the button to the order's tracking page. */
    public static Rendered actionNeeded(OrderNotification n, String instruction, EmailTemplate.Brand brand,
                                        String trackUrl, String publicUrl) {
        List<EmailTemplate.InfoCard> cards = List.of(
                EmailTemplate.InfoCard.of("◆", "Order Number", "#" + n.publicRef()),
                EmailTemplate.InfoCard.of("●", "Service", n.serviceLabel()),
                EmailTemplate.InfoCard.accented("▸", "Order Status", "On hold"));
        EmailTemplate.Content content = new EmailTemplate.Content(
                HOLD_INTRO,
                "▸",
                null,
                "ACTION NEEDED ON YOUR GFS ORDER",
                HOLD_INTRO,
                cards,
                EmailTemplate.paragraphs(instruction,
                        "When it's done, or if you have a question, tell us in your order's Discord ticket or at "
                                + publicUrl + "/support. Replies to this email do not reach us.",
                        NO_PASSWORD_BY_EMAIL),
                null,
                List.of(),
                null,
                "TRACK YOUR ORDER",
                trackUrl,
                orderFooterNote());
        return new Rendered("Action needed on order " + n.publicRef(), EmailTemplate.render(content, brand),
                actionNeededText(n, instruction, trackUrl, publicUrl));
    }

    // ---------------------------------------------------------------- delivered

    private static final String TERMS_NOTICE = "Please note that under our Terms, receipt of this email closes the "
            + "refund window for this order.";

    public static String deliveredSubject(OrderNotification n) {
        return "Order " + n.publicRef() + " delivered";
    }

    /**
     * The delivery email as plain text: a contractual notice, word for word as it has always
     * been sent.
     *
     * @param trackUrl the order's tracking page, or null for none (orders other than coins)
     */
    public static String deliveredText(OrderNotification n, String trackUrl) {
        return """
                Your order is complete.

                Reference: %s
                Service:   %s
                Total:     %s
                %s
                Two things worth doing now:

                  1. Change your EA password and regenerate your backup codes. We have
                     already deleted everything you gave us, and rotating is good hygiene
                     regardless.
                  2. Keep this email. Our seven-day guarantee runs from today — if
                     anything happens to the account in that window, reply to this message.

                Please note that under our Terms, receipt of this email closes the refund
                window for this order.

                — Global FUT Services
                """.formatted(n.publicRef(), n.serviceLabel(), n.amountFormatted(),
                trackUrl == null ? "" : "\nTrack your order: " + trackUrl + "\n");
    }

    /** The delivery email for a coin order: branded, with the button to the order's tracking page. */
    public static Rendered orderDelivered(OrderNotification n, EmailTemplate.Brand brand, String trackUrl) {
        List<EmailTemplate.InfoCard> cards = List.of(
                EmailTemplate.InfoCard.of("◆", "Order Number", "#" + n.publicRef()),
                EmailTemplate.InfoCard.of("●", "Service", n.serviceLabel()),
                EmailTemplate.InfoCard.accented("✓", "Order Status", "Completed"),
                EmailTemplate.InfoCard.of("■", "Total", n.amountFormatted()));
        // Paragraphs rather than the numbered steps block: the template draws its main button
        // only for an email without steps, and this one needs the button.
        EmailTemplate.Content content = new EmailTemplate.Content(
                "Your order is complete.",
                "✓",
                null,
                "YOUR GFS ORDER IS COMPLETE",
                "Your order is complete.",
                cards,
                EmailTemplate.paragraphs(
                        "Two things worth doing now:",
                        "1. Change your EA password and regenerate your backup codes. We have already deleted "
                                + "everything you gave us, and rotating is good hygiene regardless.",
                        "2. Keep this email. Our seven-day guarantee runs from today — if anything happens to the "
                                + "account in that window, reply to this message."),
                null,
                List.of(),
                TERMS_NOTICE,
                "TRACK YOUR ORDER",
                trackUrl,
                orderFooterNote());
        return new Rendered(deliveredSubject(n), EmailTemplate.render(content, brand), deliveredText(n, trackUrl));
    }

    /** The coaching title and the brief's text, above the Connect with Coach button. */
    private static String coachBlock() {
        return "<p style=\"margin:0 0 6px 0;font-size:18px;font-weight:800;\">" + COACH_TITLE + "</p>\n"
                + EmailTemplate.paragraphs(COACH_BODY);
    }

    /**
     * Why this email arrived.
     *
     * <p>Order mail is sent because somebody placed an order, not because they agreed to
     * hear from marketing, and it carries no unsubscribe link for that reason — turning
     * these off would mean a customer not being told their payment failed. Saying so here
     * is what stops the two kinds of email being confused for each other, and it is the
     * sentence that makes the promotional unsubscribe honest when it says "promotional".
     */
    private static String orderFooterNote() {
        return "You are receiving this email because you placed an order with Global FUT "
                + "Services. This is a transactional message about that order, not marketing.";
    }
}
