package com.globalfutservice.domain.coaching;

/**
 * Movements in a customer's session-credit balance.
 *
 * <p>An append-only ledger for the same reason the points wallet is one: "why do I have
 * two sessions left?" has to be answerable from history. A balance column would need to be
 * correct after every cancellation, expiry, coach no-show and operator correction; a sum
 * over entries is correct by construction, and the statement that explains it comes free.
 */
public enum SessionCreditType {

    /** An order granted credits. One row per order, enforced by a unique index. */
    GRANTED(1),

    /** A session was booked. Held immediately, not at attendance — see below. */
    CONSUMED(-1),

    /** A cancellation gave the credit back. */
    RETURNED(1),

    /** Credits went unused past their validity window. */
    EXPIRED(-1),

    /** Operator correction, always attributed and always with a reason. */
    MANUAL_ADJUSTMENT(0);

    private final int sign;

    SessionCreditType(int sign) {
        this.sign = sign;
    }

    /** 0 for MANUAL_ADJUSTMENT, which may go either way. */
    public int sign() {
        return sign;
    }

    /**
     * Credits are consumed at <b>booking</b>, not at attendance.
     *
     * <p>Deducting on attendance would let one credit hold six slots at once: nothing would
     * stop a customer booking every evening this week and turning up to one. Holding at
     * booking makes the balance mean "sessions you may still schedule", which is the
     * question the booking screen is actually asking, and cancellation returns it.
     */
    public static SessionCreditType onBooking() {
        return CONSUMED;
    }
}
