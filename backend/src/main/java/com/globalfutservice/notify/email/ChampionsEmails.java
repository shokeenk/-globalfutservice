package com.globalfutservice.notify.email;

import com.globalfutservice.notify.OrderNotification;
import com.globalfutservice.notify.SettlementNotice;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The two Champions emails, in the branded template the coin emails use: the delivery
 * email, with the result and what is owed, and the settlement email once it is paid or
 * issued.
 *
 * <p>Both are built from the order's result alone ({@link SettlementNotice}), never from
 * its history, so a staff-only note has no way in. Both point the customer to the order's
 * own live chat.
 */
public final class ChampionsEmails {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("d MMM uuuu, HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY_TIME = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);
    private static final String LINK = "color:#C1281B;font-weight:700;text-decoration:underline;";

    private ChampionsEmails() {
    }

    /**
     * "18 Oct 2026, 10:30 IST (05:00 UTC)": India time, with UTC beside it for a customer
     * anywhere else. The UTC side carries its own date when it falls on another day.
     */
    public static String chooseBy(Instant at) {
        ZonedDateTime ist = at.atZone(IST);
        ZonedDateTime utc = at.atZone(ZoneOffset.UTC);
        String utcPart = ist.toLocalDate().equals(utc.toLocalDate()) ? TIME.format(utc) : SHORT_DAY_TIME.format(utc);
        return DAY_TIME.format(ist) + " IST (" + utcPart + " UTC)";
    }

    public static String deliveredSubject(OrderNotification n) {
        return "Order " + n.publicRef() + " delivered — your Champions results";
    }

    /** The delivery email for a Champions order with its result. */
    public static TransactionalEmails.Rendered delivered(OrderNotification n, EmailTemplate.Brand brand,
                                                         String resultUrl, String supportUrl) {
        SettlementNotice s = n.settlement();
        List<EmailTemplate.InfoCard> cards = new ArrayList<>();
        cards.add(EmailTemplate.InfoCard.of("◆", "Order Number", "#" + n.publicRef()));
        cards.add(EmailTemplate.InfoCard.of("●", "Service", n.serviceLabel()));
        cards.add(EmailTemplate.InfoCard.of("■", "Wins Ordered", wins(s.targetLevel())));
        cards.add(EmailTemplate.InfoCard.accented("✓", "Wins Achieved", wins(s.achievedLevel())));
        if (s.settlementFormatted() != null) {
            cards.add(EmailTemplate.InfoCard.accented("▲", "Settlement Amount", s.settlementFormatted()));
        }

        List<String> lead = new ArrayList<>();
        String choice = null;
        if (s.allAchieved()) {
            lead.add("All wins achieved: we reached all " + s.targetLevel() + " of the wins you ordered.");
        } else {
            lead.add("We achieved " + s.achievedLevel() + " of the " + s.targetLevel() + " wins you ordered.");
            if (s.settlementFormatted() != null && s.chooseBy() != null) {
                lead.add(s.settlementFormatted() + " is due back to you for the wins we did not achieve. "
                        + "You can choose a refund or GFS Store Credit.");
                choice = "Choose on your order page by " + chooseBy(s.chooseBy()) + ". If you don't choose, you'll "
                        + "receive " + (s.defaultIsStoreCredit() ? "GFS Store Credit." : "a refund.");
            } else if (s.settlementFormatted() != null && s.method() != null) {
                lead.add(s.settlementFormatted() + " is due back to you for the wins we did not achieve, as "
                        + methodWords(s.method()) + ".");
            }
        }

        String body = EmailTemplate.paragraphs(lead.toArray(String[]::new))
                + (choice == null ? "" : "\n" + EmailTemplate.paragraphs(choice))
                + "\n" + EmailTemplate.paragraphs(
                        "Two things worth doing now:",
                        "1. Change your EA password and regenerate your backup codes. We have already deleted "
                                + "everything you gave us, and rotating is good hygiene regardless.",
                        "2. Keep this email. Our seven-day guarantee runs from today — if anything happens to the "
                                + "account in that window, reply to this message.")
                + "\n" + liveChat(supportUrl);

        EmailTemplate.Content content = new EmailTemplate.Content(
                "Your Champions results: " + s.achievedLevel() + " of " + s.targetLevel() + " wins.",
                "✓",
                null,
                "YOUR CHAMPIONS ORDER IS COMPLETE",
                "Your order is complete.",
                cards,
                body,
                null,
                List.of(),
                TransactionalEmails.TERMS_NOTICE,
                "VIEW YOUR CHAMPIONS RESULTS",
                resultUrl,
                TransactionalEmails.orderFooterNote());

        StringBuilder text = new StringBuilder();
        text.append("YOUR CHAMPIONS ORDER IS COMPLETE\n\n")
                .append("Order #").append(n.publicRef()).append('\n')
                .append(n.serviceLabel()).append("\n\n")
                .append("Wins Ordered:  ").append(wins(s.targetLevel())).append('\n')
                .append("Wins Achieved: ").append(wins(s.achievedLevel())).append('\n');
        if (s.settlementFormatted() != null) {
            text.append("Settlement Amount: ").append(s.settlementFormatted()).append('\n');
        }
        text.append('\n');
        for (String line : lead) {
            text.append(line).append("\n\n");
        }
        if (choice != null) {
            text.append(choice).append("\n\n");
        }
        text.append("View your Champions results: ").append(resultUrl).append("\n\n")
                .append("""
                        Two things worth doing now:

                          1. Change your EA password and regenerate your backup codes. We have
                             already deleted everything you gave us, and rotating is good hygiene
                             regardless.
                          2. Keep this email. Our seven-day guarantee runs from today — if
                             anything happens to the account in that window, reply to this message.

                        """)
                .append("For assistance, contact our Live Chat Support: ").append(supportUrl).append("\n\n")
                .append("Please note that under our Terms, receipt of this email closes the\nrefund window for this order.\n\n")
                .append("— Global FUT Services\n");
        return new TransactionalEmails.Rendered(deliveredSubject(n), EmailTemplate.render(content, brand),
                text.toString());
    }

    public static String settledSubject(OrderNotification n) {
        SettlementNotice s = n.settlement();
        return "REFUND".equals(s.method())
                ? "Your refund of " + s.settlementFormatted() + " has been sent — order " + n.publicRef()
                : s.settlementFormatted() + " GFS Store Credit added — order " + n.publicRef();
    }

    /** Sent when the settlement is Paid (refund) or Issued (store credit), including by the default. */
    public static TransactionalEmails.Rendered settled(OrderNotification n, EmailTemplate.Brand brand,
                                                       String resultUrl, String supportUrl) {
        SettlementNotice s = n.settlement();
        boolean refund = "REFUND".equals(s.method());
        List<EmailTemplate.InfoCard> cards = new ArrayList<>();
        cards.add(EmailTemplate.InfoCard.of("◆", "Order Number", "#" + n.publicRef()));
        cards.add(EmailTemplate.InfoCard.of("■", "Wins Ordered", wins(s.targetLevel())));
        cards.add(EmailTemplate.InfoCard.of("●", "Wins Achieved", wins(s.achievedLevel())));
        cards.add(EmailTemplate.InfoCard.accented("▲", refund ? "Refund Amount" : "Store Credit Amount",
                s.settlementFormatted()));
        cards.add(EmailTemplate.InfoCard.of("✓", "Status", refund ? "Refund Paid" : "Store Credit Issued"));

        String main;
        if (refund) {
            main = "We've sent your refund of " + s.settlementFormatted() + " for the wins we did not achieve. "
                    + "Depending on your bank or wallet, it can take a few days to show.";
        } else if (s.automatic()) {
            main = "You didn't choose within the time allowed, so " + s.settlementFormatted()
                    + " has been issued automatically as GFS Store Credit for the wins we did not achieve. "
                    + "It is in your account now.";
        } else {
            main = s.settlementFormatted() + " of GFS Store Credit for the wins we did not achieve is in your "
                    + "account now.";
        }
        String more = refund ? null
                : "Use it at checkout on your next order in the same currency. It does not expire, and it can be "
                        + "combined with a coupon and your reward points.";

        String body = EmailTemplate.paragraphs(main, more) + "\n" + liveChat(supportUrl);
        EmailTemplate.Content content = new EmailTemplate.Content(
                refund ? "Your refund has been sent." : "Your GFS Store Credit has been added.",
                "✓",
                null,
                refund ? "YOUR REFUND HAS BEEN SENT" : "YOUR GFS STORE CREDIT IS READY",
                "Your Champions settlement for order " + n.publicRef() + ".",
                cards,
                body,
                null,
                List.of(),
                null,
                "VIEW YOUR ORDER",
                resultUrl,
                TransactionalEmails.orderFooterNote());

        String text = (refund ? "YOUR REFUND HAS BEEN SENT" : "YOUR GFS STORE CREDIT IS READY") + "\n\n"
                + "Order #" + n.publicRef() + "\n"
                + "Wins Ordered:  " + wins(s.targetLevel()) + "\n"
                + "Wins Achieved: " + wins(s.achievedLevel()) + "\n"
                + (refund ? "Refund Amount: " : "Store Credit Amount: ") + s.settlementFormatted() + "\n"
                + "Status: " + (refund ? "Refund Paid" : "Store Credit Issued") + "\n\n"
                + main + "\n\n"
                + (more == null ? "" : more + "\n\n")
                + "View your order: " + resultUrl + "\n\n"
                + "For assistance, contact our Live Chat Support: " + supportUrl + "\n\n"
                + "— Global FUT Services\n";
        return new TransactionalEmails.Rendered(settledSubject(n), EmailTemplate.render(content, brand), text);
    }

    private static String liveChat(String supportUrl) {
        return "<p style=\"margin:0 0 12px 0;\">For assistance, contact our <a href=\"" + EmailTemplate.esc(supportUrl)
                + "\" style=\"" + LINK + "\">Live Chat Support</a>.</p>";
    }

    private static String wins(int n) {
        return n + (n == 1 ? " Win" : " Wins");
    }

    private static String methodWords(String method) {
        return "REFUND".equals(method) ? "a refund" : "GFS Store Credit";
    }
}
