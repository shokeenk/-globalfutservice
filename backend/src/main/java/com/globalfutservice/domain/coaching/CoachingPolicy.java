package com.globalfutservice.domain.coaching;

import java.time.Duration;
import java.time.Instant;

/**
 * Every tunable number in the booking rules, in one place, injected from configuration.
 *
 * <p>Same principle as {@code PricingPolicy}: none of these is a literal buried in a
 * service. "How much notice must a customer give to get their credit back" is a published
 * promise on the coaching page and a line in the Terms, and it has to be changeable
 * without a redeploy of booking logic — and readable from exactly one place when a
 * customer disputes it.
 *
 * @param sessionLength        how long a single purchased session runs
 * @param blockSessionLength   how long one session from a multi-session block runs
 *
 *                             <p>A second length rather than a single one, because the
 *                             business sells two different products: an hour bought on
 *                             its own, and a shorter session bought six at a time at a
 *                             lower price per session. The price difference IS the
 *                             duration difference, so a booking system with one length
 *                             cannot charge for the block honestly — it either gives
 *                             away twenty minutes a session or advertises a length it
 *                             does not deliver.
 * @param slotStep             granularity of the booking grid — 30m means slots start on
 *                             the hour and the half hour, never at 14:07
 * @param minLeadTime          how soon before a slot it may still be booked. Protects the
 *                             coach from a booking landing four minutes before it starts
 * @param maxAdvance           how far into the future the calendar is open. Bounds slot
 *                             generation, and stops someone booking into next season
 * @param changeCutoff         notice required to cancel or reschedule without losing the
 *                             credit
 * @param maxReschedules       how many times one session may be moved before the customer
 *                             has to cancel and rebook
 * @param noShowGrace          how long after the start time a coach waits before the
 *                             session may be marked NO_SHOW
 * @param creditValidity       how long session credits remain usable after purchase
 */
public record CoachingPolicy(
        Duration sessionLength,
        Duration blockSessionLength,
        Duration slotStep,
        Duration minLeadTime,
        Duration maxAdvance,
        Duration changeCutoff,
        int maxReschedules,
        Duration noShowGrace,
        Duration creditValidity) {

    public CoachingPolicy {
        requirePositive(sessionLength, "sessionLength");
        requirePositive(blockSessionLength, "blockSessionLength");
        requirePositive(slotStep, "slotStep");
        requirePositive(maxAdvance, "maxAdvance");
        requirePositive(creditValidity, "creditValidity");
        if (minLeadTime == null || minLeadTime.isNegative()) {
            throw new IllegalArgumentException("minLeadTime must not be negative");
        }
        if (changeCutoff == null || changeCutoff.isNegative()) {
            throw new IllegalArgumentException("changeCutoff must not be negative");
        }
        if (noShowGrace == null || noShowGrace.isNegative()) {
            throw new IllegalArgumentException("noShowGrace must not be negative");
        }
        if (maxReschedules < 0) {
            throw new IllegalArgumentException("maxReschedules must not be negative");
        }
        if (maxAdvance.compareTo(minLeadTime) <= 0) {
            throw new IllegalArgumentException("maxAdvance must exceed minLeadTime");
        }
    }

    /**
     * The launch configuration: 40-minute sessions on a 30-minute grid, bookable from two
     * hours out to sixty days ahead, twelve hours' notice to move or cancel, two
     * reschedules per session, fifteen minutes' grace before a no-show, credits good for
     * thirty days.
     *
     * <p>Thirty rather than ninety because the block is sold as a month: six sessions in
     * thirty days is roughly one and a half a week, which is the pace the product
     * describes. A ninety-day window would quietly turn a monthly programme into a
     * three-month one and change what the price is buying.
     *
     * <p>The twelve-hour cutoff is deliberately generous relative to what the coach loses:
     * it is long enough to refill the slot and short enough that a customer who wakes up
     * to a changed plan the evening before is not punished for it.
     */
    public static CoachingPolicy launchDefaults() {
        return new CoachingPolicy(
                Duration.ofMinutes(60),   // a single session
                Duration.ofMinutes(40),   // one session out of a block
                Duration.ofMinutes(30),
                Duration.ofHours(2),
                Duration.ofDays(60),
                Duration.ofHours(12),
                2,
                Duration.ofMinutes(15),
                Duration.ofDays(30));
    }

    /**
     * Whether a customer cancelling now gets their credit back.
     *
     * <p>The single question the whole cancellation policy reduces to, answered in one
     * place so the storefront warning, the API and the Terms cannot drift apart. The
     * storefront calls this to decide whether to warn "you will lose this session" before
     * the customer confirms — the same method, so the warning is never wrong.
     */
    public boolean refundsCreditOnCustomerCancel(Instant now, Instant sessionStart) {
        return !now.isAfter(sessionStart.minus(changeCutoff));
    }

    /** Whether a session may still be moved, given how many times it already has been. */
    public boolean canReschedule(Instant now, Instant sessionStart, int rescheduleCount) {
        return rescheduleCount < maxReschedules
                && !now.isAfter(sessionStart.minus(changeCutoff));
    }

    /** The earliest instant a newly-booked slot may start. */
    public Instant earliestBookableStart(Instant now) {
        return now.plus(minLeadTime);
    }

    /** The latest instant the calendar exposes. */
    public Instant latestBookableStart(Instant now) {
        return now.plus(maxAdvance);
    }

    /** When a session booked for this start time may be marked NO_SHOW. */
    public Instant noShowEligibleAt(Instant sessionStart) {
        return sessionStart.plus(noShowGrace);
    }

    /** The instant credits bought now stop being usable. */
    public Instant creditsExpireAt(Instant purchasedAt) {
        return purchasedAt.plus(creditValidity);
    }

    private static void requirePositive(Duration d, String name) {
        if (d == null || d.isNegative() || d.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
