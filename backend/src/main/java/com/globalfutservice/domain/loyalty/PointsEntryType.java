package com.globalfutservice.domain.loyalty;

/**
 * Movements in the points wallet. The wallet is an append-only ledger, never a mutable
 * balance column: the balance is the sum of entries, so a disputed total can always be
 * reconstructed from history rather than argued about.
 *
 * <p>Each entry carries a second, independent property: whether it moves the customer's
 * <b>lifetime</b> total, which is what {@link LoyaltyTier} reads. Balance and lifetime
 * answer different questions and deliberately diverge — see {@link #countsTowardLifetime()}.
 */
public enum PointsEntryType {
    /** Granted when an order reaches COMPLETED. Positive. */
    EARNED(1, true),
    /** Spent at checkout. Negative. */
    REDEEMED(-1, false),
    /** Returned to the customer when a redeeming order is refunded. Positive. */
    REFUND_REVERSAL(1, false),
    /** Removed after a completed order was later reversed. Negative. */
    CLAWBACK(-1, true),
    /** Goodwill or promotional grant, always attributed to an operator. Positive. */
    MANUAL_ADJUSTMENT(1, true),
    /** Daily check-in grant. Positive, once per account per calendar day. */
    DAILY_BONUS(1, true);

    private final int sign;
    private final boolean countsTowardLifetime;

    PointsEntryType(int sign, boolean countsTowardLifetime) {
        this.sign = sign;
        this.countsTowardLifetime = countsTowardLifetime;
    }

    public int sign() {
        return sign;
    }

    /**
     * Whether this entry moves the lifetime total that drives {@link LoyaltyTier}.
     *
     * <p>Three of these are load-bearing:
     *
     * <ul>
     *   <li><b>REDEEMED does not count.</b> Spending points must not demote anyone. Status
     *       is earned by trading with us; withdrawing it for using the reward we granted
     *       is a support ticket waiting to happen.
     *   <li><b>REFUND_REVERSAL does not count.</b> It returns previously-spent points to
     *       the balance. Since the spend never reduced lifetime, crediting it back would
     *       inflate lifetime for free — redeem, refund, repeat, and climb to Diamond
     *       without spending anything.
     *   <li><b>CLAWBACK does count</b>, negatively. Otherwise the ladder is trivially
     *       farmable: place a large order, let it complete, bank the lifetime credit,
     *       then reverse it and keep the tier discount forever.
     * </ul>
     */
    public boolean countsTowardLifetime() {
        return countsTowardLifetime;
    }
}
