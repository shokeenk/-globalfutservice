package com.globalfutservice.notify;

import java.time.Instant;

/**
 * A coaching session was booked, moved, or called off.
 *
 * <p>Separate from {@link CoachingNotification}, which is the reminder sent to the
 * customer an hour before. This one is addressed to the people running the business: it
 * carries the things an operator needs in order to act — who, when, which session of how
 * many, which order, and whether the money has arrived — and the reminder carries none of
 * them because a customer does not need to be told their own payment status.
 *
 * @param sessionRef       the session's public reference
 * @param startsAt         when it now starts. Always UTC; rendering is the channel's job
 * @param endsAt           when it ends, so a calendar entry does not have to guess
 * @param previousStartsAt what it moved from, on a reschedule. Null otherwise
 * @param customerTimezone the IANA zone the customer booked in, which is the only zone
 *                         they will recognise their own session in. Nullable — an older
 *                         session may predate the column
 * @param customerEmail    who booked it
 * @param customerName     their name if the order carried one, else null
 * @param coachName        the coach, for a business that may one day have two
 * @param sessionNumber    which session of the pack this is, counting from one
 * @param sessionsInPack   how many the pack held, so "3 of 6" can be written
 * @param orderRef         the order whose credits paid for it. Nullable: a session booked
 *                         from a manual adjustment has no order behind it
 * @param paymentStatus    the order's status, or null where there is no order
 *
 * <p>There is deliberately no ticket channel id here. Which channel an order's ticket
 * lives in is a Discord question, and answering it costs an API call; putting it in this
 * record would make the coaching service ask it even when no Discord channel is
 * configured. The notifier resolves it from {@code orderRef} instead, on the async thread
 * where a round trip is free.
 */
public record CoachingBookingNotification(
        String sessionRef,
        Instant startsAt,
        Instant endsAt,
        Instant previousStartsAt,
        String customerTimezone,
        String customerEmail,
        String customerName,
        String coachName,
        int sessionNumber,
        int sessionsInPack,
        String orderRef,
        String paymentStatus) {

    /** "3 of 6", or just "3" where the pack size is not known. */
    public String sessionLabel() {
        if (sessionsInPack <= 0) {
            return sessionNumber <= 0 ? "—" : String.valueOf(sessionNumber);
        }
        return sessionNumber + " of " + sessionsInPack;
    }
}
