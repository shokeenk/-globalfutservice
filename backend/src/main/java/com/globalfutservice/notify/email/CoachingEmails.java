package com.globalfutservice.notify.email;

import com.globalfutservice.notify.CoachingBookingNotification;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a customer is sent when a coaching session is booked, moved or called off.
 *
 * <p>Separate from {@link TransactionalEmails}, which is about orders and takes an
 * {@code OrderNotification}. A session is not an order — it has no amount, no payment
 * status worth showing a customer, and the thing they need from the message is a time
 * they can put in a calendar.
 *
 * <p><b>One zone, theirs.</b> Everything here renders in the zone the customer booked in.
 * The staff channel gets IST as well because a coach needs it; a customer does not, and
 * printing two times is how somebody turns up at the wrong one.
 */
public final class CoachingEmails {

    private static final DateTimeFormatter WHEN = DateTimeFormatter
            .ofPattern("EEEE d MMMM 'at' HH:mm")
            .withLocale(Locale.UK);

    private CoachingEmails() {
    }

    public static TransactionalEmails.Rendered booked(CoachingBookingNotification n,
                                                      EmailTemplate.Brand brand,
                                                      ZoneId zone, String manageUrl) {
        String when = format(n.startsAt(), zone);
        return render(n, brand, zone, manageUrl,
                "Your coaching session is booked",
                "YOUR COACHING SESSION IS BOOKED",
                "✓",
                // "The day before", because that is when the reminder sweep actually
                // runs — CoachingLifecycleJobs picks up the hour beginning 24 hours out.
                "You're booked in. The details are below — add it to your calendar with the "
                        + "attachment, and we'll remind you the day before.",
                when,
                "Your session is confirmed. If you need to move it, you can do that from your "
                        + "account up to the cut-off shown there, and the session goes straight "
                        + "back to your balance.");
    }

    public static TransactionalEmails.Rendered rescheduled(CoachingBookingNotification n,
                                                           EmailTemplate.Brand brand,
                                                           ZoneId zone, String manageUrl) {
        String when = format(n.startsAt(), zone);
        String was = n.previousStartsAt() == null ? null : format(n.previousStartsAt(), zone);
        return render(n, brand, zone, manageUrl,
                "Your coaching session has moved",
                "YOUR COACHING SESSION HAS MOVED",
                "▸",
                was == null
                        ? "Your session has been moved. The new time is below."
                        : "Your session has been moved from " + was + ". The new time is below.",
                when,
                "The calendar attachment replaces the entry you already have rather than "
                        + "adding a second one.");
    }

    public static TransactionalEmails.Rendered cancelled(CoachingBookingNotification n,
                                                         EmailTemplate.Brand brand,
                                                         ZoneId zone, String manageUrl) {
        String when = format(n.startsAt(), zone);
        return render(n, brand, zone, manageUrl,
                "Your coaching session is cancelled",
                "YOUR COACHING SESSION IS CANCELLED",
                "▪",
                "This session has been cancelled and the credit is back on your account.",
                when,
                "Book another whenever you like — your remaining sessions are on your account "
                        + "page, along with when they expire.");
    }

    private static TransactionalEmails.Rendered render(
            CoachingBookingNotification n, EmailTemplate.Brand brand, ZoneId zone,
            String manageUrl, String subject, String headline, String statusIcon,
            String intro, String when, String closing) {

        List<EmailTemplate.InfoCard> cards = new ArrayList<>();
        cards.add(EmailTemplate.InfoCard.accented("◆", "When",
                when + " (" + zone.getId() + ")"));
        if (!"—".equals(n.sessionLabel())) {
            cards.add(EmailTemplate.InfoCard.of("●", "Session", n.sessionLabel()));
        }
        if (n.coachName() != null && !n.coachName().isBlank()) {
            cards.add(EmailTemplate.InfoCard.of("▸", "Coach", n.coachName()));
        }

        EmailTemplate.Content content = new EmailTemplate.Content(
                intro,
                statusIcon,
                null,
                headline,
                intro,
                cards,
                EmailTemplate.paragraphs(closing),
                null,
                List.of(),
                null,
                "MANAGE YOUR SESSIONS",
                manageUrl,
                // Not marketing, and therefore no unsubscribe link: somebody who opted
                // out of promotions still needs to be told their session moved.
                "You are receiving this because you booked a coaching session with Global "
                        + "FUT Services. This is a message about that session, not marketing.");

        String text = """
                %s

                %s

                When:      %s (%s)
                Session:   %s
                Reference: %s

                Manage your sessions: %s

                %s

                — Global FUT Services
                """.formatted(headline, intro, when, zone.getId(), n.sessionLabel(),
                n.sessionRef(), manageUrl, closing);

        return new TransactionalEmails.Rendered(subject,
                EmailTemplate.render(content, brand), text);
    }

    private static String format(java.time.Instant at, ZoneId zone) {
        return WHEN.format(at.atZone(zone));
    }
}
