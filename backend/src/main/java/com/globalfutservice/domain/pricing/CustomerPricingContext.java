package com.globalfutservice.domain.pricing;

/**
 * Everything about the buyer that can move the price.
 *
 * <p>All of it is read server-side from the authenticated session and the database. None
 * of it is accepted from the request body — a client that can assert
 * {@code walletPoints: 999999} is a client that can buy for free.
 *
 * @param walletPoints    the customer's confirmed redeemable balance
 * @param requestedPoints how many they asked to spend on this order (may exceed balance;
 *                        the engine clamps rather than throwing, so the UI can be lenient)
 * @param lifetimePoints  every point ever earned, never reduced by spending. Drives the
 *                        {@link com.globalfutservice.domain.loyalty.LoyaltyTier} ladder,
 *                        and is a different number from {@code walletPoints} on purpose
 * @param firstOrder      true when this customer has never had an order reach COMPLETED
 * @param referralCode    creator code supplied at checkout, already validated as active
 * @param referralDiscountBps discount attached to that code, or 0 when none applies
 * @param couponCode      coupon typed at checkout, already validated as live, within its
 *                        redemption limits and above its minimum — null when none applies
 * @param couponDiscountBps discount attached to that coupon, or 0 when none applies. The
 *                        engine clamps this to {@link Coupon#MAX_DISCOUNT_BPS} regardless
 *                        of what it is handed, because a discount arriving from a caller
 *                        is the one number in this record most worth distrusting
 */
public record CustomerPricingContext(
        long walletPoints,
        long requestedPoints,
        long lifetimePoints,
        boolean firstOrder,
        String referralCode,
        int referralDiscountBps,
        String couponCode,
        int couponDiscountBps) {

    public CustomerPricingContext {
        if (walletPoints < 0) {
            throw new IllegalArgumentException("walletPoints must not be negative");
        }
        if (requestedPoints < 0) {
            throw new IllegalArgumentException("requestedPoints must not be negative");
        }
        if (lifetimePoints < 0) {
            throw new IllegalArgumentException("lifetimePoints must not be negative");
        }
        if (referralDiscountBps < 0 || referralDiscountBps > 10_000) {
            throw new IllegalArgumentException("referralDiscountBps out of range");
        }
        if (couponDiscountBps < 0) {
            throw new IllegalArgumentException("couponDiscountBps must not be negative");
        }
        if (couponDiscountBps > Coupon.MAX_DISCOUNT_BPS) {
            throw new IllegalArgumentException(
                    "couponDiscountBps exceeds the coupon ceiling");
        }
    }

    /** A guest checkout: no balance, no history, no code, treated as a first order. */
    public static CustomerPricingContext guest() {
        return new CustomerPricingContext(0L, 0L, 0L, true, null, 0, null, 0);
    }

    /** True when a validated coupon should actually move the price. */
    public boolean hasCoupon() {
        return couponCode != null && !couponCode.isBlank() && couponDiscountBps > 0;
    }
}
