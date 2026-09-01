package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.money.Currency;

import java.time.Duration;

/**
 * Every tunable number in the pricing engine, in one place, injected from configuration.
 *
 * <p>Nothing here is a literal buried in a service. A rate change is a config change plus
 * a rate-card insert, never a redeploy of business logic.
 *
 * @param marketTaxBps            EA transfer-market tax in basis points (500 = 5%)
 * @param gatewayFeeBps           payment-processing fee in basis points (250 = 2.5%)
 * @param gatewayFeeMode          how that fee is applied
 * @param maxWalletRedemptionBps  ceiling on how much of one order points may pay for
 *                                (2000 = 20%). Without a ceiling, a large balance can
 *                                zero out an order, which makes refund maths ugly and
 *                                gives fraud somewhere to hide.
 * @param loyaltyCurrency         the currency the three loyalty parameters below are
 *                                denominated in. Points are earned and redeemed
 *                                <b>only</b> on orders settled in this currency.
 *
 *                                <p>Without this field the parameters were bare scalars
 *                                applied to whatever currency an order happened to be in,
 *                                and a shared points balance made that directly
 *                                exploitable: spend ₹2,000, earn 20 points, then switch
 *                                the storefront to USD and redeem the same 20 points for
 *                                $20 off. Same points, roughly eighty-eight times the
 *                                value.
 *
 *                                <p>This is the same rule {@link
 *                                com.globalfutservice.domain.money.Currency} already
 *                                states for prices — authored per currency, never
 *                                FX-converted at runtime. The loyalty parameters were the
 *                                one place that broke it.
 * @param pointValueMinor         redemption value of one point, in minor units of
 *                                {@code loyaltyCurrency} (100 = ₹1)
 * @param earnSpendUnitMinor      spend granularity that earns points, in minor units of
 *                                {@code loyaltyCurrency} (200000 = ₹2,000)
 * @param earnPointsPerUnit       points granted per spend unit (20)
 * @param quoteTtl                how long a signed quote stays valid
 * @param tierDiscountEnabled     whether the {@link com.globalfutservice.domain.loyalty.LoyaltyTier}
 *                                ladder discounts orders automatically. A switch rather
 *                                than a redeploy, because it stacks with the points rebate
 *                                and the combined margin cost is a business decision the
 *                                owner may need to reverse on a bad month — at DIAMOND the
 *                                two together are roughly 6% of gross
 */
public record PricingPolicy(
        int marketTaxBps,
        /** Whether the listed price already covers EA's cut, or it is added at checkout. */
        MarketTaxMode marketTaxMode,
        int gatewayFeeBps,
        GatewayFeeMode gatewayFeeMode,
        int maxWalletRedemptionBps,
        Currency loyaltyCurrency,
        long pointValueMinor,
        long earnSpendUnitMinor,
        long earnPointsPerUnit,
        Duration quoteTtl,
        boolean tierDiscountEnabled) {

    public PricingPolicy {
        if (marketTaxBps < 0 || marketTaxBps > 10_000) {
            throw new IllegalArgumentException("marketTaxBps out of range");
        }
        if (gatewayFeeBps < 0 || gatewayFeeBps >= 10_000) {
            throw new IllegalArgumentException("gatewayFeeBps out of range");
        }
        if (maxWalletRedemptionBps < 0 || maxWalletRedemptionBps > 10_000) {
            throw new IllegalArgumentException("maxWalletRedemptionBps out of range");
        }
        if (loyaltyCurrency == null) {
            throw new IllegalArgumentException("loyaltyCurrency is required");
        }
        if (pointValueMinor <= 0 || earnSpendUnitMinor <= 0 || earnPointsPerUnit < 0) {
            throw new IllegalArgumentException("loyalty parameters must be positive");
        }
        if (quoteTtl == null || quoteTtl.isNegative() || quoteTtl.isZero()) {
            throw new IllegalArgumentException("quoteTtl must be positive");
        }
    }

    /**
     * Whether an order in this currency participates in the loyalty programme at all.
     *
     * <p>Both halves — earning and redeeming — are gated on the same answer. Gating only
     * one would be worse than gating neither: earn-only quietly strands balances that can
     * never be spent, and redeem-only is the eighty-eight-times exploit with extra steps.
     */
    public boolean earnsLoyalty(Currency currency) {
        return loyaltyCurrency == currency;
    }

    /**
     * The client's launch configuration: 5% EA market tax, 2.5% processing passed
     * through, points worth ₹1 each earned at 20 per ₹2,000 (a 1% effective rebate),
     * redeemable for at most 20% of any single order, quotes valid ten minutes, and the
     * six-tier ladder discounting automatically on top.
     *
     * <p>Loyalty settles in INR only. Orders in USD, EUR or GBP neither earn nor spend
     * points until someone decides what a point is worth in those currencies — that is a
     * pricing decision, not an engineering one, and guessing it with an FX rate is the
     * exact thing this codebase refuses to do for prices.
     */
    public static PricingPolicy launchDefaults() {
        return new PricingPolicy(
                500,
                MarketTaxMode.INCLUDED,
                250,
                GatewayFeeMode.PASS_THROUGH,
                2_000,
                Currency.INR,
                100L,
                200_000L,
                20L,
                Duration.ofMinutes(10),
                true);
    }
}
