package com.globalfutservice.domain.coaching;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns a coach's recurring weekly availability into concrete bookable instants.
 *
 * <p>This is the one piece of the booking system where being casually wrong produces bugs
 * that only appear twice a year, in one hemisphere, and are unreproducible on the
 * developer's machine. So the rules it follows are written down:
 *
 * <ol>
 *   <li><b>Iterate in the coach's local calendar, not in UTC.</b> "Every Tuesday 18:00" is
 *       a statement about local dates. Walking the range in UTC days and converting drifts
 *       across any zone whose offset is not a whole number of hours, and breaks entirely
 *       across a DST change.
 *   <li><b>Fit the session inside the window in local time.</b> A 40-minute session in an
 *       18:00–22:00 window may start at 21:20 and not at 21:30, and that comparison is
 *       meaningful in the coach's wall clock, which is what they used to declare it.
 *   <li><b>Measure the session's actual length in absolute time.</b> Once a start instant
 *       is fixed, forty minutes is forty real minutes. A session is not an hour longer
 *       because the clocks went back during it.
 *   <li><b>Deduplicate the results.</b> On a spring-forward day the local times 02:00 and
 *       02:30 may not exist; {@link ZonedDateTime#of} resolves both forward, and two
 *       distinct local slots can land on the same instant. Returning it twice would offer
 *       a slot that double-books itself.
 * </ol>
 *
 * <p><b>A known and accepted limitation:</b> on a fall-back day the repeated local hour
 * resolves to its earlier offset, so the second pass through 01:30 is never offered. The
 * alternative is offering two visually identical slots an hour apart, which is worse for
 * everyone involved. One slot per year per coach goes unsold; nobody is ever confused.
 *
 * <p>Framework-free and deterministic — every input is a value, so the awkward days can be
 * tested directly rather than waited for.
 */
public final class SlotPlanner {

    private SlotPlanner() {
    }

    /**
     * Every instant at which a session could legally start.
     *
     * @param coachZone  the coach's zone — the frame their availability is declared in
     * @param rules      recurring weekly windows
     * @param busy       time already spoken for: booked sessions and time off
     * @param window     absolute bounds to generate within, normally
     *                   {@code [now + minLeadTime, now + maxAdvance)}
     * @param policy     session length and grid granularity
     * @return start instants in ascending order, each free and fully inside a window
     */
    public static List<Instant> bookableStarts(
            ZoneId coachZone,
            Collection<AvailabilityRule> rules,
            Collection<TimeRange> busy,
            TimeRange window,
            CoachingPolicy policy) {

        return bookableStarts(coachZone, rules, busy, window, policy,
                policy == null ? null : policy.sessionLength());
    }

    /**
     * As above, for a session of an explicitly given length.
     *
     * <p>Exists because session length is a property of what the customer bought, not of
     * the coach: a block session runs shorter than a single one. Passing the length in
     * rather than reading it off the policy is what lets one coach's calendar be laid out
     * correctly for both products, and it keeps the two callers provably identical -- the
     * offered list and the server-side legality check go through the same code, which is
     * the invariant that stops a slot becoming offerable but unbookable.
     *
     * @param length how long the session being booked runs; must be positive
     */
    public static List<Instant> bookableStarts(
            ZoneId coachZone,
            Collection<AvailabilityRule> rules,
            Collection<TimeRange> busy,
            TimeRange window,
            CoachingPolicy policy,
            Duration length) {

        if (coachZone == null || rules == null || busy == null || window == null
                || policy == null || length == null) {
            throw new IllegalArgumentException("all arguments are required");
        }
        if (length.isZero() || length.isNegative()) {
            throw new IllegalArgumentException("length must be positive");
        }
        if (rules.isEmpty()) {
            return List.of();
        }

        Duration step = policy.slotStep();

        // Widen by a day on each side so a window whose local date differs from its UTC
        // date — which is most of Asia and all of the Americas — cannot clip its own edges.
        LocalDate firstDate = window.start().atZone(coachZone).toLocalDate().minusDays(1);
        LocalDate lastDate = window.end().atZone(coachZone).toLocalDate().plusDays(1);

        Set<Instant> starts = new LinkedHashSet<>();

        for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
            for (AvailabilityRule rule : rules) {
                if (rule.day() != date.getDayOfWeek()) {
                    continue;
                }
                collectWindow(date, rule, coachZone, length, step, window, busy, starts);
            }
        }

        List<Instant> ordered = new ArrayList<>(starts);
        ordered.sort(Instant::compareTo);
        return List.copyOf(ordered);
    }

    private static void collectWindow(
            LocalDate date,
            AvailabilityRule rule,
            ZoneId coachZone,
            Duration length,
            Duration step,
            TimeRange window,
            Collection<TimeRange> busy,
            Set<Instant> out) {

        LocalTime cursor = rule.start();

        // Rule 2: the session must finish inside the declared window, judged on the
        // coach's wall clock. Walking LocalTime also means the loop cannot run away on a
        // day that is 23 or 25 hours long.
        while (!cursor.plus(length).isAfter(rule.end())) {

            ZonedDateTime local = ZonedDateTime.of(LocalDateTime.of(date, cursor), coachZone);
            Instant start = local.toInstant();

            // Re-check the date after resolution: inside a DST gap the instant can land on
            // the following local day, outside the window the coach actually declared.
            if (local.toLocalDate().equals(date)) {
                TimeRange candidate = TimeRange.of(start, length);
                if (fitsWindow(candidate, window) && isFree(candidate, busy)) {
                    out.add(start);
                }
            }

            LocalTime next = cursor.plus(step);
            if (!next.isAfter(cursor)) {
                break; // stepped over midnight; the window does not wrap
            }
            cursor = next;
        }
    }

    /** The whole session, not merely its start, must sit inside the bookable window. */
    private static boolean fitsWindow(TimeRange candidate, TimeRange window) {
        return !candidate.start().isBefore(window.start())
                && !candidate.end().isAfter(window.end());
    }

    private static boolean isFree(TimeRange candidate, Collection<TimeRange> busy) {
        for (TimeRange taken : busy) {
            if (candidate.overlaps(taken)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether one specific requested start is legal.
     *
     * <p>The storefront picks from {@link #bookableStarts}, but the server must never
     * trust that the instant it receives came from that list — a hand-crafted request can
     * name any instant at all. Booking re-derives the answer here, from the same rules,
     * against a freshly-read busy set inside the booking transaction.
     */
    public static boolean isBookable(
            Instant requestedStart,
            ZoneId coachZone,
            Collection<AvailabilityRule> rules,
            Collection<TimeRange> busy,
            TimeRange window,
            CoachingPolicy policy) {

        return isBookable(requestedStart, coachZone, rules, busy, window, policy,
                policy == null ? null : policy.sessionLength());
    }

    /** As above, for a session of an explicitly given length. */
    public static boolean isBookable(
            Instant requestedStart,
            ZoneId coachZone,
            Collection<AvailabilityRule> rules,
            Collection<TimeRange> busy,
            TimeRange window,
            CoachingPolicy policy,
            Duration length) {

        if (requestedStart == null) {
            return false;
        }
        // Generating the full list and searching it guarantees this answer and the offered
        // list are produced by identical logic. Re-implementing the check separately is how
        // the two drift and a slot becomes offerable but unbookable.
        return bookableStarts(coachZone, rules, busy, window, policy, length)
                .contains(requestedStart);
    }
}
