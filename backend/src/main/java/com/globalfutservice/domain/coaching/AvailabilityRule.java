package com.globalfutservice.domain.coaching;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * One recurring window in which a coach works, expressed in <b>the coach's own local
 * time</b>: "Tuesdays, 18:00 to 22:00".
 *
 * <p><b>Why local time and not instants.</b> A coach who says they work weekday evenings
 * means 18:00 <i>their</i> evening, in perpetuity. Storing that as a UTC instant freezes
 * today's offset into the rule, and the whole schedule silently slides by an hour the next
 * time their region changes clocks — the coach keeps their stated hours on paper while the
 * calendar quietly starts selling 17:00 slots. Storing the local time and resolving it
 * against the coach's zone at generation time is the only version that survives a DST
 * change without anybody noticing it happened.
 *
 * @param day   the weekday, in the coach's zone
 * @param start first moment of the window, inclusive
 * @param end   end of the window, exclusive — a session must finish by this time
 */
public record AvailabilityRule(DayOfWeek day, LocalTime start, LocalTime end) {

    public AvailabilityRule {
        if (day == null || start == null || end == null) {
            throw new IllegalArgumentException("day, start and end are required");
        }
        // Windows do not wrap past midnight. A coach working 22:00–02:00 declares two
        // rules on two weekdays, which keeps the generator from having to reason about
        // a window whose end date differs from its start date.
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("availability window must end after it starts");
        }
    }
}
