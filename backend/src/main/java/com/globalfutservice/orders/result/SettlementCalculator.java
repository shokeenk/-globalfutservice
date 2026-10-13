package com.globalfutservice.orders.result;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The settlement arithmetic, and nothing else: pure, so every rule is pinned by a test.
 *
 * <p><b>The amount.</b> The ordered tier's list price less the achieved tier's, scaled by
 * the discount the customer was actually given on the service:
 * <pre>
 *   settlement = (ordered price − achieved price) × (paid for the service ÷ ordered price)
 * </pre>
 * "Paid for the service" leaves out the payment fee -- no part of the 2.5% or a Payop
 * method's fee is ever paid back -- and is after the coupon, tier discount and points. A
 * 15-win order at ₹4,000 with a 5% coupon, 14 wins achieved (₹3,500): ₹500 × 0.95 =
 * ₹475. Rounded half up to the currency's minor unit, and never more than was paid.
 */
public final class SettlementCalculator {

    private SettlementCalculator() {
    }

    /** What is owed for a result on the ladder. Zero when the achieved tier is priced at or above the ordered one. */
    public static long settlement(long orderedPriceMinor, long achievedPriceMinor, long servicePaidMinor) {
        if (orderedPriceMinor <= 0 || servicePaidMinor <= 0) {
            return 0L;
        }
        long difference = orderedPriceMinor - achievedPriceMinor;
        if (difference <= 0) {
            return 0L;
        }
        long scaled = BigDecimal.valueOf(difference)
                .multiply(BigDecimal.valueOf(servicePaidMinor))
                .divide(BigDecimal.valueOf(orderedPriceMinor), 0, RoundingMode.HALF_UP)
                .longValueExact();
        return Math.min(scaled, servicePaidMinor);
    }

    /**
     * A settlement typed by hand, below the lowest tier: accepted between zero and what was
     * paid for the service.
     *
     * @throws IllegalArgumentException outside that range
     */
    public static long manual(long amountMinor, long servicePaidMinor) {
        if (amountMinor < 0 || amountMinor > servicePaidMinor) {
            throw new IllegalArgumentException("A settlement is between zero and what was paid for the service");
        }
        return amountMinor;
    }

    /**
     * Loyalty points the settlement takes back: the share of the order's points the settled
     * amount paid for, rounded to the nearest whole point, and never more than it earned.
     */
    public static long pointsRemoved(long pointsEarned, long settlementMinor, long servicePaidMinor) {
        if (pointsEarned <= 0 || settlementMinor <= 0 || servicePaidMinor <= 0) {
            return 0L;
        }
        long removed = BigDecimal.valueOf(pointsEarned)
                .multiply(BigDecimal.valueOf(settlementMinor))
                .divide(BigDecimal.valueOf(servicePaidMinor), 0, RoundingMode.HALF_UP)
                .longValueExact();
        return Math.min(removed, pointsEarned);
    }

    /**
     * The money a full refund sends back once part of the order has been settled: the total
     * less what was settled, refund or store credit alike. Never below zero.
     */
    public static long moneyBackOnFullRefund(long totalMinor, long settledMinor) {
        return Math.max(0L, totalMinor - Math.max(0L, settledMinor));
    }

    /**
     * The store credit a full refund gives back from what the order spent: all of it, less
     * any part of the settlement the money refund could not cover. Between them the customer
     * gets back exactly what they paid, money and credit, less what was already settled --
     * and never credit as cash.
     */
    public static long creditBackOnFullRefund(long creditUsedMinor, long totalMinor, long settledMinor) {
        long uncovered = Math.max(0L, Math.max(0L, settledMinor) - totalMinor);
        return Math.max(0L, creditUsedMinor - uncovered);
    }

    /**
     * Whether the settlement can be paid back as money. Not when the order was paid partly
     * with store credit and the settlement is more than the money paid for the service:
     * credit is never paid out as cash, so that settlement can only be store credit.
     */
    public static boolean refundAllowed(long settlementMinor, long servicePaidMinor, long storeCreditUsedMinor) {
        return settlementMinor <= Math.max(0L, servicePaidMinor - storeCreditUsedMinor);
    }
}
