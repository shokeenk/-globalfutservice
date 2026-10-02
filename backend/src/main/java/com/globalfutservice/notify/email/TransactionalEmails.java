package com.globalfutservice.notify.email;

import com.globalfutservice.notify.OrderNotification;

import java.util.ArrayList;
import java.util.List;

/**
 * The two emails the order lifecycle sends a customer.
 *
 * <p>One when they tell us they have paid, one when we confirm we found the money. The
 * specification consolidates a longer list down to exactly these two, and this class is
 * the whole set — anything else arriving in a customer's inbox about an order comes from
 * somewhere else and should be questioned.
 *
 * <p><b>What is deliberately NOT folded into these two.</b> The delivery email is a
 * contractual notice — the published Terms run the guarantee window from delivery, and
 * that email is how the customer learns the window has started — so it is left alone. So
 * is the credentials request, without which an order stalls in silence. Consolidating the
 * <i>payment-status</i> emails is what this is; deleting the notice the Terms rely on
 * would be a different and much worse change.
 *
 * <p>Both render into {@link EmailTemplate}, so the header, the footer links and the trust
 * badges are defined once and cannot drift apart.
 */
public final class TransactionalEmails {

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
                                          String trackUrl, String discordUrl, String coachUrl) {
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

        List<EmailTemplate.Step> steps = !discord ? List.of() : List.of(
                new EmailTemplate.Step(
                        "Join our Discord server",
                        "Click the button below to join the GFS Discord server.",
                        "JOIN DISCORD", discordUrl),
                EmailTemplate.Step.of(
                        "Your ticket will be created",
                        "Once you join, you'll be directed to your order ticket automatically."),
                EmailTemplate.Step.of(
                        "Our Operations Executive will contact you",
                        "One of our operations executives will connect with you through your "
                                + "ticket and guide you through the next steps."));

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
                discord ? "Please make sure to join with the same email/Discord account used for "
                          + "placing the order, to ensure a smooth and quick verification."
                        : coaching ? COACH_SIGN_IN : null,
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
                    1. Join our Discord server: %s
                    2. Your ticket will be created automatically once you join.
                    3. Our Operations Executive will contact you through your ticket.

                  Please join with the same email/Discord account used for placing the
                  order, to ensure a smooth and quick verification.""".formatted(discordUrl);

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
