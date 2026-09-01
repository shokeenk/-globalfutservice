package com.globalfutservice.loyalty;

import com.globalfutservice.domain.loyalty.LoyaltyTier;

/**
 * A customer's standing on the loyalty ladder.
 *
 * <p>Assembled server-side and rendered as-is. The storefront never recomputes a tier from
 * a points balance — that is how a rewards page ends up disagreeing with a checkout total,
 * which is the exact drift the reference site suffers between its "5% cashback" homepage
 * copy and its 5%-discount-tier rewards page.
 *
 * @param tier             current rung
 * @param lifetimePoints   points ever earned, the number the ladder reads
 * @param balancePoints    points available to spend, a different number on purpose
 * @param pointsToNextTier 0 at the top of the ladder
 * @param discountBps      what the tier is actually worth right now — 0 when tier
 *                         discounts are switched off, so the UI shows the truth rather
 *                         than the ladder's nominal rate
 * @param canClaimDaily    whether the check-in bonus is still unclaimed today
 * @param dailyBonusPoints what claiming it grants
 */
public record LoyaltyStatus(
        LoyaltyTier tier,
        long lifetimePoints,
        long balancePoints,
        long pointsToNextTier,
        int discountBps,
        boolean canClaimDaily,
        long dailyBonusPoints) {
}
