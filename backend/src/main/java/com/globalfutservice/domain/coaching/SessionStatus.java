package com.globalfutservice.domain.coaching;

/**
 * The lifecycle of one booked coaching slot.
 *
 * <p>Note what is <i>not</i> here: there is no {@code RESCHEDULED}. A reschedule moves the
 * start time of a session that stays SCHEDULED, and is recorded as an event rather than a
 * state. Modelling it as a status would mean a session could sit in a state that is
 * indistinguishable from "booked" for every practical purpose while needing its own row in
 * every transition table — and it would lose the count of how many times a customer has
 * moved the same session, which is the thing an operator actually wants to know.
 *
 * <p>Every terminal state records whether the session credit was returned. That is stored
 * on the session, not inferred from the status, because the answer for a customer
 * cancellation depends on <i>when</i> they cancelled — see {@link CoachingPolicy}.
 */
public enum SessionStatus {

    /** Booked and in the future, or in progress right now. */
    SCHEDULED(false),

    /** The session happened. The credit is consumed. Terminal. */
    COMPLETED(true),

    /**
     * The customer called it off. Whether the credit comes back depends on how much
     * notice they gave.
     */
    CANCELLED_BY_CUSTOMER(true),

    /** The coach called it off. The credit always comes back, without exception. */
    CANCELLED_BY_COACH(true),

    /**
     * The customer did not turn up and did not cancel. The credit is consumed — the coach
     * held the slot and turned away other bookings for it.
     */
    NO_SHOW(true);

    private final boolean terminal;

    SessionStatus(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** True where the slot no longer occupies the coach's calendar. */
    public boolean releasesSlot() {
        return this == CANCELLED_BY_CUSTOMER || this == CANCELLED_BY_COACH;
    }
}
