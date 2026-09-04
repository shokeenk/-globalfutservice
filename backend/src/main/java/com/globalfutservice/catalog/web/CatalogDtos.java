package com.globalfutservice.catalog.web;

import java.math.BigDecimal;
import java.util.List;

/** Wire shapes for the public catalogue. */
public final class CatalogDtos {

    private CatalogDtos() {
    }

    public record CatalogResponse(
            String season,
            String currency,
            List<String> availableCurrencies,
            List<ServiceGroup> services) {
    }

    public record ServiceGroup(
            String sku,
            String displayName,
            boolean sellable,
            String priceUnit,
            boolean marketTaxApplies,
            boolean mayRequireCredentials,
            List<CatalogOption> options) {
    }

    public record CatalogOption(
            String platform,
            String variant,
            String label,
            long unitPriceMinor,
            String unitPriceFormatted,
            BigDecimal minQuantity,
            BigDecimal maxQuantity,
            BigDecimal stepQuantity,
            /**
             * Measured success rate for this package, in basis points, or {@code null}.
             *
             * <p><b>Null today, and null is the honest answer.</b> A success rate for a
             * boosting package means "of the orders that asked for 15 wins, what share
             * reached 15 wins" — and nothing in this application records the second half
             * of that. {@code OrderStatus} runs to DELIVERED and COMPLETED, which say an
             * order finished, never what rank it finished at. There is no
             * {@code achievedWins} column to aggregate.
             *
             * <p>The field exists so the storefront can be built against the real shape
             * and so turning this on later is a service change rather than a UI project.
             * It is deliberately not defaulted to a plausible number: a percentage beside
             * a buy button is a claim that induces the purchase, and one invented to fill
             * the slot is indistinguishable to a customer, a regulator or a payment
             * provider from one that was measured.
             *
             * <p>To make it real: record the achieved outcome on the order at delivery,
             * then aggregate achieved-versus-ordered over a window long enough to be
             * meaningful and large enough not to identify individual orders.
             */
            Integer successRateBps) {
    }

    /**
     * Every number the storefront needs in order to describe the offer.
     *
     * <p>This endpoint exists because of a specific failure mode worth avoiding: the
     * reference site this business is modelled on promises "5% cashback on every order"
     * on its homepage while its rewards page tops out at a 5% <i>discount tier</i> — the
     * marketing copy drifted away from the rules engine and nobody noticed. Here the
     * storefront renders its loyalty and guarantee copy from the same configuration the
     * pricing engine reads, so the two cannot disagree.
     */
    public record PolicyResponse(
            int marketTaxBps,
            int gatewayFeeBps,
            String gatewayFeeMode,
            /**
             * The currency loyalty settles in.
             *
             * <p>Served because the storefront has to be able to tell the truth. Points
             * are earned and redeemed only on orders in this currency, and a rewards page
             * that promises "20 points per $2,000" to a customer browsing in USD — when
             * the engine will award none — is exactly the copy-versus-engine drift this
             * endpoint exists to prevent.
             */
            String loyaltyCurrency,
            long pointValueMinor,
            long earnSpendUnitMinor,
            long earnPointsPerUnit,
            int maxWalletRedemptionBps,
            long quoteTtlSeconds,
            long guaranteeDays,
            long deliverySlaHours,
            int refundFeeBps,
            int guaranteeCashBps,
            int guaranteeCreditBps,
            String defaultDeliveryMethod,
            /**
             * The loyalty ladder, served rather than hard-coded in the storefront.
             *
             * <p>Same reasoning as everything else on this endpoint. A rewards page that
             * spells out "Gold — 2,000 points — 2%" in JSX is a second copy of the ladder,
             * and the second copy is the one that stops matching after somebody tunes the
             * first. Serving it means the page cannot promise a rung the engine does not
             * price.
             */
            List<TierView> loyaltyTiers,
            /** False when tier discounts are switched off, so the page can say so. */
            boolean tierDiscountEnabled,
            long dailyBonusPoints,
            /**
             * How long one coaching session runs, in minutes.
             *
             * <p>Same reasoning as the ladder above. The homepage states this on a spec
             * plate and the storefront had it typed in as "40"; when the session moved to
             * an hour the config, the slot planner, the price list and the booking page
             * all followed, and the one number a visitor actually reads did not. Served
             * from the same property the planner uses, it cannot disagree.
             */
            int coachingSessionMinutes,
            /** Length of one session from a multi-session block. */
            int coachingBlockSessionMinutes) {
    }

    public record TierView(
            String name,
            String displayName,
            long thresholdPoints,
            int discountBps) {
    }
}
