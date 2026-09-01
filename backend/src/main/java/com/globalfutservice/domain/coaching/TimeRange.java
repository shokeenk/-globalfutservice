package com.globalfutservice.domain.coaching;

import java.time.Duration;
import java.time.Instant;

/**
 * A half-open interval on the absolute timeline, {@code [start, end)}.
 *
 * <p>Half-open is the whole point: a session ending at 19:00 and one starting at 19:00 do
 * <b>not</b> overlap, so back-to-back bookings are legal and the coach's calendar packs
 * without gaps. Closed intervals get this wrong in the direction that costs money — every
 * adjacent slot pair looks like a conflict and half the calendar becomes unbookable.
 */
public record TimeRange(Instant start, Instant end) {

    public TimeRange {
        if (start == null || end == null) {
            throw new IllegalArgumentException("start and end are required");
        }
        if (!end.isAfter(start)) {
            throw new IllegalArgumentException("range must end after it starts");
        }
    }

    public static TimeRange of(Instant start, Duration length) {
        return new TimeRange(start, start.plus(length));
    }

    /** True when the two ranges share any instant. Half-open, so touching is not overlap. */
    public boolean overlaps(TimeRange other) {
        return start.isBefore(other.end) && other.start.isBefore(end);
    }

    public boolean contains(Instant instant) {
        return !instant.isBefore(start) && instant.isBefore(end);
    }
}
