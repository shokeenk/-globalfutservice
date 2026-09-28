package com.globalfutservice.orders;

import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

/**
 * When staff may send a customer another "please add your EA sign-in" email.
 *
 * <p>The reminder is the same email the order sent when it was paid, sent again by hand.
 * The limit is what stops the button becoming a way to email somebody every few minutes:
 * one reminder in any six hours, however many operators are looking at the queue.
 *
 * <p>A reminder is recorded on the order's timeline as an event that leaves the status
 * where it was, CREDENTIALS_PENDING to CREDENTIALS_PENDING, made by an operator. Nothing
 * else writes that shape, so it identifies a reminder without a table of its own. The
 * customer's order page shows the event's reason, which is written for them.
 */
public final class CredentialReminders {

    public static final Duration GAP = Duration.ofHours(6);

    /** What the customer reads on their order page. */
    public static final String REASON = "We sent you a reminder to add your EA sign-in so we can start.";

    private CredentialReminders() {
    }

    static boolean isReminder(OrderEventEntity event) {
        return event.getActorType() == Actor.OPERATOR
                && event.getFromStatus() == OrderStatus.CREDENTIALS_PENDING
                && event.getToStatus() == OrderStatus.CREDENTIALS_PENDING;
    }

    /**
     * When the next reminder may go, or empty if it may go now.
     *
     * @param sent when earlier reminders went, in any order
     */
    public static Optional<Instant> notBefore(Collection<Instant> sent, Instant now) {
        Instant latest = sent.stream().max(Instant::compareTo).orElse(null);
        if (latest == null) {
            return Optional.empty();
        }
        Instant next = latest.plus(GAP);
        return next.isAfter(now) ? Optional.of(next) : Optional.empty();
    }
}
