package com.globalfutservice.domain.loyalty;

/**
 * The six-rung loyalty ladder, keyed on <b>lifetime</b> points.
 *
 * <p><b>Lifetime, not balance.</b> A customer who spends their points must not fall down
 * a tier — status is earned by trading with us, and taking it away for using the reward
 * we granted is the kind of detail that produces support tickets and cancelled accounts.
 * So {@link com.globalfutservice.domain.loyalty.PointsEntryType#EARNED} and
 * {@code DAILY_BONUS} entries accumulate forever into a separate running total, and
 * {@code REDEEMED} never touches it. The two numbers answer different questions: balance
 * is "what can I spend", lifetime is "who am I".
 *
 * <p><b>Why the ladder lives in code and the rate cards do not.</b> Prices are data
 * because they change between promos and every historical order has to stay explainable.
 * The tier ladder is the opposite: it is a published promise on a marketing page, it
 * changes almost never, and when it does change it changes for everyone at once with no
 * historical reconstruction needed. Encoding it here buys exhaustive unit testing and a
 * compiler that catches a missing rung. The one thing that <i>is</i> configurable is
 * whether the discount applies at all — see {@code PricingPolicy.tierDiscountEnabled}.
 *
 * <p>Discounts are basis points, matching every other rate in the pricing engine.
 */
public enum LoyaltyTier {

    BRONZE("Bronze", 0L, 0),
    SILVER("Silver", 500L, 100),
    GOLD("Gold", 2_000L, 200),
    ELITE("Elite", 5_000L, 300),
    PLATINUM("Platinum", 8_000L, 400),
    DIAMOND("Diamond", 10_000L, 500);

    private final String displayName;
    private final long thresholdPoints;
    private final int discountBps;

    LoyaltyTier(String displayName, long thresholdPoints, int discountBps) {
        this.displayName = displayName;
        this.thresholdPoints = thresholdPoints;
        this.discountBps = discountBps;
    }

    public String displayName() {
        return displayName;
    }

    /** Lifetime points at which this tier is reached. */
    public long thresholdPoints() {
        return thresholdPoints;
    }

    /** Automatic discount applied to every order at this tier, in basis points. */
    public int discountBps() {
        return discountBps;
    }

    /**
     * The tier a customer with this many lifetime points sits in.
     *
     * <p>Walks down from the top so the highest satisfied threshold wins. A negative
     * balance is impossible by construction but is treated as BRONZE rather than throwing:
     * a pricing call is the wrong place to discover a ledger bug, and the safe direction
     * to fail is "no discount".
     */
    public static LoyaltyTier forLifetimePoints(long lifetimePoints) {
        LoyaltyTier[] ladder = values();
        for (int i = ladder.length - 1; i >= 0; i--) {
            if (lifetimePoints >= ladder[i].thresholdPoints) {
                return ladder[i];
            }
        }
        return BRONZE;
    }

    /** The next rung up, or empty at the top. */
    public LoyaltyTier next() {
        LoyaltyTier[] ladder = values();
        int i = ordinal();
        return i + 1 < ladder.length ? ladder[i + 1] : null;
    }

    /**
     * Points still needed for the next rung, or 0 at DIAMOND.
     *
     * <p>Drives the "480 points to Gold" line on the rewards page. Rendered from the same
     * ladder that prices the order, so the two can never disagree.
     */
    public long pointsToNext(long lifetimePoints) {
        LoyaltyTier next = next();
        if (next == null) {
            return 0L;
        }
        return Math.max(0L, next.thresholdPoints - lifetimePoints);
    }

    /** True once this tier actually saves the customer money. */
    public boolean hasDiscount() {
        return discountBps > 0;
    }
}
