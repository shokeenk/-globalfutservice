package com.globalfutservice.notify.email;

import com.globalfutservice.notify.CoachingBookingNotification;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * One coaching session as an {@code .ics} file.
 *
 * <p>Hand-written rather than pulled from a library. iCalendar is a large specification
 * and this uses a thin slice of it — one non-recurring event, in UTC, with no attendees
 * and no alarms — so a dependency would buy nothing but a supply chain.
 *
 * <p><b>Everything is UTC.</b> A {@code DTSTART} ending in {@code Z} needs no VTIMEZONE
 * block and cannot be misread: the calendar application renders it in whoever is reading
 * it. Emitting a local time with a named zone would mean shipping the zone definition
 * too, and getting it wrong shifts a session by hours in one direction for one reader.
 */
public final class CalendarInvite {

    /** Basic format, UTC, as iCalendar requires: 20261001T133000Z. */
    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private CalendarInvite() {
    }

    /**
     * @param method {@code REQUEST} for a booking or a move, {@code CANCEL} to withdraw
     *               one. A cancel carries the same UID, which is how a calendar knows to
     *               remove the event it already has rather than add a second
     */
    public static byte[] forSession(CoachingBookingNotification n, String method,
                                    String website) {
        /*
         * The UID is the session reference and never changes. A rescheduled session sends
         * a second REQUEST with the same UID and a higher SEQUENCE, which every calendar
         * treats as "move the one you have" -- without that the customer accumulates one
         * entry per reschedule and turns up to the first.
         */
        String uid = n.sessionRef() + "@globalfutservices.com";
        int sequence = "CANCEL".equals(method) ? 2 : (n.previousStartsAt() == null ? 0 : 1);

        StringBuilder ics = new StringBuilder(512);
        line(ics, "BEGIN:VCALENDAR");
        line(ics, "VERSION:2.0");
        line(ics, "PRODID:-//Global FUT Services//Coaching//EN");
        line(ics, "CALSCALE:GREGORIAN");
        line(ics, "METHOD:" + method);
        line(ics, "BEGIN:VEVENT");
        line(ics, "UID:" + uid);
        line(ics, "SEQUENCE:" + sequence);
        line(ics, "DTSTAMP:" + STAMP.format(Instant.now()));
        line(ics, "DTSTART:" + STAMP.format(n.startsAt()));
        line(ics, "DTEND:" + STAMP.format(endOf(n)));
        line(ics, "SUMMARY:" + escape(summary(n)));
        line(ics, "DESCRIPTION:" + escape(description(n, website)));
        line(ics, "STATUS:" + ("CANCEL".equals(method) ? "CANCELLED" : "CONFIRMED"));
        line(ics, "END:VEVENT");
        line(ics, "END:VCALENDAR");
        return ics.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Falls back to 40 minutes, the length of a session out of a pack. */
    private static Instant endOf(CoachingBookingNotification n) {
        if (n.endsAt() != null && n.endsAt().isAfter(n.startsAt())) {
            return n.endsAt();
        }
        return n.startsAt().plusSeconds(40 * 60);
    }

    private static String summary(CoachingBookingNotification n) {
        String label = n.sessionLabel();
        return "—".equals(label)
                ? "EA FC coaching session"
                : "EA FC coaching session " + label;
    }

    private static String description(CoachingBookingNotification n, String website) {
        StringBuilder body = new StringBuilder();
        if (n.coachName() != null && !n.coachName().isBlank()) {
            body.append("Coach: ").append(n.coachName()).append('\n');
        }
        body.append("Reference: ").append(n.sessionRef()).append('\n');
        if (n.orderRef() != null) {
            body.append("Order: ").append(n.orderRef()).append('\n');
        }
        body.append("Manage this session: ").append(website).append("/coaching");
        return body.toString();
    }

    /**
     * iCalendar's own escaping: backslash, semicolon, comma and newline.
     *
     * <p>Not HTML escaping. A coach's name with a comma in it silently truncates a
     * property otherwise, because the comma is a value separator in this format.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\n", "\\n");
    }

    /**
     * One property, folded to the line length the format allows.
     *
     * <p>RFC 5545 caps a line at 75 octets and continues longer ones on the next line
     * with a single leading space. A description carrying a coach's name, two references
     * and a URL goes well past that, and a strict parser rejects the whole file rather
     * than the line — so this is not cosmetic.
     *
     * <p>Counted in <b>octets, not characters</b>: the file is UTF-8 and an accented
     * name is two bytes per character, so folding on character count would still produce
     * over-long lines for exactly the names most likely to appear.
     */
    private static void line(StringBuilder out, String content) {
        byte[] utf8 = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (utf8.length <= 75) {
            out.append(content).append("\r\n");
            return;
        }

        int start = 0;
        boolean first = true;
        while (start < utf8.length) {
            // A continuation line spends one octet on its leading space.
            int budget = first ? 75 : 74;
            int end = Math.min(start + budget, utf8.length);
            // Never split a multi-byte character: back off to a boundary.
            while (end > start && end < utf8.length && (utf8[end] & 0xC0) == 0x80) {
                end--;
            }
            String chunk = new String(utf8, start, end - start,
                    java.nio.charset.StandardCharsets.UTF_8);
            out.append(first ? "" : " ").append(chunk).append("\r\n");
            start = end;
            first = false;
        }
    }
}
