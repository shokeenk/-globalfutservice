package com.globalfutservice.notify;

import java.time.Instant;

/**
 * A notification about a coaching session.
 *
 * <p>Carries the start time as an instant plus the zone the customer booked in, rather than
 * a pre-formatted string. Formatting is the channel's job: an email renders it one way, a
 * WhatsApp template another, and baking one rendering in here would mean the second channel
 * has to unpick it.
 *
 * @param customerTimezone the zone they booked in, or null to fall back to the business zone
 */
public record CoachingNotification(
        String customerEmail,
        String sessionRef,
        Instant startsAt,
        String customerTimezone) {
}
