package com.globalfutservice.notify.email;

import com.globalfutservice.notify.CoachingBookingNotification;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calendar entry a customer actually keeps.
 *
 * <p>Worth its own tests because nothing downstream validates it: a malformed {@code .ics}
 * is silently ignored by most clients, so a mistake here is invisible until somebody does
 * not turn up. The assertions are about the three things that decide whether the entry
 * lands in the right place at the right time — the timestamp format, the UID, and the
 * escaping.
 */
class CalendarInviteTest {

    private static final Instant START = Instant.parse("2026-10-01T13:30:00Z");

    private static CoachingBookingNotification session(String coach, Instant previous) {
        return new CoachingBookingNotification(
                "SES-XYZ", START, START.plusSeconds(40 * 60), previous,
                "Europe/London", "player@example.com", "Sam", coach,
                3, 6, "GFS-26-ABCD1234", "PAID");
    }

    private static String ics(CoachingBookingNotification n, String method) {
        return new String(CalendarInvite.forSession(n, method, "https://globalfutservices.com"),
                StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("times are UTC, so no client has to be told which zone they are in")
    void timesAreUtc() {
        String out = ics(session("Vinay", null), "REQUEST");

        assertThat(out).contains("DTSTART:20261001T133000Z");
        assertThat(out).contains("DTEND:20261001T141000Z");
    }

    @Test
    @DisplayName("a move keeps the same UID, so the calendar replaces rather than duplicates")
    void rescheduleReusesTheUid() {
        String first = ics(session("Vinay", null), "REQUEST");
        String moved = ics(session("Vinay", START.minusSeconds(86_400)), "REQUEST");

        assertThat(first).contains("UID:SES-XYZ@globalfutservices.com");
        assertThat(moved).contains("UID:SES-XYZ@globalfutservices.com");
        // A calendar only honours the replacement if the sequence has moved on.
        assertThat(first).contains("SEQUENCE:0");
        assertThat(moved).contains("SEQUENCE:1");
    }

    @Test
    @DisplayName("a cancellation withdraws the entry rather than adding one")
    void cancellationWithdraws() {
        String out = ics(session("Vinay", null), "CANCEL");

        assertThat(out).contains("METHOD:CANCEL");
        assertThat(out).contains("STATUS:CANCELLED");
        assertThat(out).contains("UID:SES-XYZ@globalfutservices.com");
    }

    @Test
    @DisplayName("a comma in a coach's name does not truncate the line it sits on")
    void escapesIcalendarSeparators() {
        String out = ics(session("Vinay, EA FC coach", null), "REQUEST");

        // Unescaped, the comma would end the value and the rest would be dropped.
        assertThat(out).contains("Vinay\\, EA FC coach");
    }

    @Test
    @DisplayName("lines end CRLF, which the format requires")
    void usesCrlf() {
        String out = ics(session("Vinay", null), "REQUEST");

        assertThat(out).startsWith("BEGIN:VCALENDAR\r\n");
        assertThat(out).endsWith("END:VCALENDAR\r\n");
    }

    @Test
    @DisplayName("no line exceeds the 75 octets the format allows")
    void longLinesAreFolded() {
        // The description carries a coach, two references and a URL, which is well past
        // the limit. A strict parser rejects the whole file, not just the line.
        String out = ics(session("Vinay", null), "REQUEST");

        for (String line : out.split("\r\n")) {
            assertThat(line.getBytes(StandardCharsets.UTF_8).length)
                    .as("line within the octet limit: %s", line)
                    .isLessThanOrEqualTo(75);
        }
    }

    @Test
    @DisplayName("folding never splits a multi-byte character in half")
    void foldingRespectsUtf8() {
        String out = ics(session("Vinäy Ünicode Coaching Naïve Sessions Ltd", null), "REQUEST");

        // A split mid-character would leave a replacement char after a round trip.
        assertThat(out).doesNotContain("�");
        assertThat(out.replace("\r\n ", "")).contains("Vinäy Ünicode");
    }

    @Test
    @DisplayName("a session with no pack position still produces a usable entry")
    void unnumberedSessionStillWorks() {
        CoachingBookingNotification manual = new CoachingBookingNotification(
                "SES-MAN", START, START.plusSeconds(2400), null, null,
                "player@example.com", null, null, 0, 0, null, null);

        String out = ics(manual, "REQUEST");

        assertThat(out).contains("SUMMARY:EA FC coaching session");
        assertThat(out).contains("UID:SES-MAN@globalfutservices.com");
        assertThat(out).doesNotContain("Order:");
    }

    @Test
    @DisplayName("a missing end time falls back to a session length rather than zero")
    void missingEndFallsBack() {
        CoachingBookingNotification noEnd = new CoachingBookingNotification(
                "SES-NE", START, null, null, "Europe/London",
                "player@example.com", null, null, 1, 6, null, null);

        assertThat(ics(noEnd, "REQUEST")).contains("DTEND:20261001T141000Z");
    }
}
