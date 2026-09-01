package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.catalog.PriceUnit;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.loyalty.LoyaltyTier;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns intent plus a rate card into a signed-able {@link Quote}.
 *
 * <p><b>Rounding contract.</b> Every component is computed in exact decimal at high
 * precision and accumulated without rounding. The <i>total</i> is rounded once, HALF_UP.
 * Each displayed line is then rounded independently, and the difference between the sum
 * of the rounded lines and the rounded total — at most a few minor units — is folded back
 * into the BASE line. The customer therefore sees a breakdown whose column adds up
 * exactly to what their card is charged, which is the only property that matters when
 * somebody screenshots an invoice and asks why it is off by one paisa.
 *
 * <p>This class is deliberately free of Spring, JPA and Jackson. It is pure arithmetic
 * over value objects, so it can be exhaustively unit-tested in milliseconds and verified
 * offline with nothing but a JDK — see {@code backend/verify-domain.sh}.
 */
public final class PricingEngine {

    /** Enough precision that no intermediate ever rounds; the total rounds once. */
    private static final MathContext MC = new MathContext(24, RoundingMode.HALF_UP);
    private static final BigDecimal BPS = BigDecimal.valueOf(10_000);

    private final PricingPolicy policy;
    private final Clock clock;
    private final QuoteIdGenerator idGenerator;

    public PricingEngine(PricingPolicy policy, Clock clock, QuoteIdGenerator idGenerator) {
        this.policy = policy;
        this.clock = clock;
        this.idGenerator = idGenerator;
    }

    public PricingPolicy policy() {
        return policy;
    }

    /**
     * @param rateCard the currently-valid row for this SKU/platform/currency
     * @param quantity millions of coins for PER_MILLION SKUs; ignored for FLAT SKUs
     * @param customer server-derived buyer context
     */
    public Quote quote(RateCard rateCard, BigDecimal quantity, CustomerPricingContext customer) {
        if (!rateCard.sku().sellable()) {
            throw new PricingException(rateCard.sku().displayName() + " is not available yet.");
        }
        BigDecimal qty = rateCard.sku().unit() == PriceUnit.FLAT ? BigDecimal.ONE : quantity;
        if (qty == null) {
            throw new PricingException("Please choose an amount.");
        }
        try {
            rateCard.validateQuantity(qty);
        } catch (IllegalArgumentException e) {
            throw new PricingException(e.getMessage());
        }

        var currency = rateCard.currency();
        var unitPrice = BigDecimal.valueOf(rateCard.unitPrice().minor());

        // ---- exact arithmetic, minor units, no rounding until the very end -----------
        BigDecimal base = unitPrice.multiply(qty, MC);

        /*
         * Charged only when the mode says the listed price does not already cover it.
         *
         * `taxApplies` is kept separate from `marketTax` because the two answer different
         * questions: whether EA's cut is relevant to this SKU at all (coaching: no), and
         * whether the customer is being charged for it here. Under INCLUDED the first is
         * still true while the second is not, and the quote needs both — it shows the line
         * at zero so the customer can see the charge they are not paying.
         */
        boolean taxApplies = rateCard.sku().marketTaxApplies() && policy.marketTaxBps() > 0;
        BigDecimal marketTax = BigDecimal.ZERO;
        if (taxApplies && policy.marketTaxMode() == MarketTaxMode.ADDED) {
            marketTax = base.multiply(BigDecimal.valueOf(policy.marketTaxBps()), MC).divide(BPS, MC);
        }

        BigDecimal subtotal = base.add(marketTax, MC);

        /*
         * Coupon and creator code are mutually exclusive, and the coupon wins.
         *
         * Both are promotional discounts on the same subtotal. Stacking them turns two
         * separately-approved offers into a third nobody signed off — a 20% coupon on top
         * of a 13% creator code is 33% off, which is below cost on coin trading. The
         * coupon takes precedence because the customer typed it deliberately in this
         * checkout, whereas a creator code is often just inherited from the account they
         * signed up under and applied silently.
         *
         * The tier discount is deliberately NOT part of this exclusion: it is earned by
         * spending, not granted by a promotion, and a customer who has reached Diamond
         * should not lose it for using a code.
         */
        boolean couponApplies = customer.hasCoupon();

        BigDecimal couponDiscount = BigDecimal.ZERO;
        if (couponApplies) {
            // Clamped again here. The context already rejects anything above the ceiling,
            // but this is the last line before money is computed and it costs nothing.
            int bps = Math.min(customer.couponDiscountBps(), Coupon.MAX_DISCOUNT_BPS);
            couponDiscount = subtotal
                    .multiply(BigDecimal.valueOf(bps), MC)
                    .divide(BPS, MC)
                    .negate();
        }

        BigDecimal referralDiscount = BigDecimal.ZERO;
        boolean referralApplies = !couponApplies
                && customer.referralCode() != null
                && customer.referralDiscountBps() > 0
                && customer.firstOrder();
        if (referralApplies) {
            referralDiscount = subtotal
                    .multiply(BigDecimal.valueOf(customer.referralDiscountBps()), MC)
                    .divide(BPS, MC)
                    .negate();
        }

        // The loyalty ladder discounts every order automatically once a customer has the
        // lifetime points for it. Computed against `subtotal`, the same base the referral
        // discount uses, so the two add rather than compound — see LineCode.TIER_DISCOUNT.
        LoyaltyTier tier = LoyaltyTier.forLifetimePoints(customer.lifetimePoints());
        BigDecimal tierDiscount = BigDecimal.ZERO;
        boolean tierApplies = policy.tierDiscountEnabled() && tier.hasDiscount();
        if (tierApplies) {
            tierDiscount = subtotal
                    .multiply(BigDecimal.valueOf(tier.discountBps()), MC)
                    .divide(BPS, MC)
                    .negate();
        }

        BigDecimal afterDiscount = subtotal
                .add(couponDiscount, MC)
                .add(referralDiscount, MC)
                .add(tierDiscount, MC);
        // Two stacked discounts cannot take an order below zero and hand the customer
        // change. Only reachable if someone configures a referral code above 95%, but the
        // failure mode is a negative charge, so it is guarded rather than assumed away.
        if (afterDiscount.signum() < 0) {
            afterDiscount = BigDecimal.ZERO;
        }

        // Wallet redemption is exact by construction: whole points at a fixed rupee value.
        long pointsRedeemed = clampRedemption(customer, afterDiscount, currency);
        BigDecimal walletRedemption = BigDecimal
                .valueOf(Math.multiplyExact(pointsRedeemed, policy.pointValueMinor()))
                .negate();

        BigDecimal net = afterDiscount.add(walletRedemption, MC);
        if (net.signum() < 0) {
            net = BigDecimal.ZERO;
        }

        BigDecimal gatewayFee = gatewayFee(net);

        BigDecimal exactTotal = net.add(gatewayFee, MC);

        // ---- single rounding point ---------------------------------------------------
        long totalMinor = exactTotal.setScale(0, RoundingMode.HALF_UP).longValueExact();

        long baseMinor = round(base);
        long marketTaxMinor = round(marketTax);
        long couponMinor = round(couponDiscount);
        long referralMinor = round(referralDiscount);
        long tierMinor = round(tierDiscount);
        long walletMinor = walletRedemption.longValueExact();
        long gatewayMinor = round(gatewayFee);

        long lineSum = baseMinor + marketTaxMinor + couponMinor + referralMinor + tierMinor
                + walletMinor + gatewayMinor;
        // Fold the sub-paisa residual into BASE so the column adds up on screen.
        baseMinor += (totalMinor - lineSum);

        List<QuoteLine> lines = new ArrayList<>(7);
        lines.add(new QuoteLine(LineCode.BASE, baseLabel(rateCard, qty), Money.ofMinor(baseMinor, currency)));
        /*
         * Shown whenever EA's cut is relevant, including when it costs nothing.
         *
         * A zero line is normally noise, and every other line here is suppressed at zero.
         * This one is the exception on purpose: "we cover the EA tax" is a claim about a
         * charge, and the claim is far more convincing standing in the row where the
         * charge would have appeared than it is as a sentence elsewhere on the page. The
         * storefront renders a zero MARKET_TAX as "Included" rather than as a currency
         * amount.
         */
        if (taxApplies || marketTaxMinor != 0) {
            lines.add(new QuoteLine(LineCode.MARKET_TAX,
                    "EA transfer market tax (" + pct(policy.marketTaxBps()) + ")",
                    Money.ofMinor(marketTaxMinor, currency)));
        }
        if (couponMinor != 0) {
            lines.add(new QuoteLine(LineCode.COUPON_DISCOUNT,
                    "Coupon " + customer.couponCode()
                            + " (" + pct(Math.min(customer.couponDiscountBps(),
                                                  Coupon.MAX_DISCOUNT_BPS)) + " off)",
                    Money.ofMinor(couponMinor, currency)));
        }
        if (referralMinor != 0) {
            lines.add(new QuoteLine(LineCode.REFERRAL_DISCOUNT,
                    "Creator code " + customer.referralCode()
                            + " (" + pct(customer.referralDiscountBps()) + " off first order)",
                    Money.ofMinor(referralMinor, currency)));
        }
        if (tierMinor != 0) {
            lines.add(new QuoteLine(LineCode.TIER_DISCOUNT,
                    tier.displayName() + " member discount (" + pct(tier.discountBps()) + ")",
                    Money.ofMinor(tierMinor, currency)));
        }
        if (walletMinor != 0) {
            lines.add(new QuoteLine(LineCode.WALLET_REDEMPTION,
                    pointsRedeemed + " reward points redeemed",
                    Money.ofMinor(walletMinor, currency)));
        }
        if (gatewayMinor != 0) {
            lines.add(new QuoteLine(LineCode.GATEWAY_FEE,
                    "Payment processing (" + pct(policy.gatewayFeeBps()) + ")",
                    Money.ofMinor(gatewayMinor, currency)));
        }

        Money total = Money.ofMinor(totalMinor, currency);
        long pointsEarned = pointsEarnedOn(total);

        Instant now = clock.instant();
        return new Quote(
                idGenerator.next(),
                rateCard.season(),
                rateCard.sku(),
                rateCard.platform(),
                rateCard.variant(),
                qty,
                currency,
                lines,
                Money.ofMinor(round(subtotal), currency),
                total,
                pointsRedeemed,
                pointsEarned,
                // Only record a code that actually moved the price. An unapplied creator
                // code recorded on the order would accrue commission for a sale it did
                // not cause; an unapplied coupon would burn a redemption for nothing.
                referralApplies ? customer.referralCode() : null,
                couponApplies ? customer.couponCode() : null,
                now,
                now.plus(policy.quoteTtl()));
    }

    /**
     * Points granted once an order reaches COMPLETED, computed on the amount actually
     * charged. Whole units only — ₹3,900 earns the same 20 points as ₹2,000, which is
     * exactly what the client specified and is deliberately not pro-rated.
     */
    public long pointsEarnedOn(Money total) {
        if (total.minor() <= 0 || policy.earnPointsPerUnit() == 0) {
            return 0L;
        }
        /*
         * Only orders settled in the loyalty currency earn.
         *
         * `earnSpendUnitMinor` is 200,000 minor units — ₹2,000. Applied to a USD order
         * the same constant silently means $2,000, so the threshold and the resulting
         * points are denominated in whatever currency happened to arrive. Combined with a
         * single shared balance that is not a rounding problem, it is free money.
         */
        if (!policy.earnsLoyalty(total.currency())) {
            return 0L;
        }
        long units = total.minor() / policy.earnSpendUnitMinor();
        return Math.multiplyExact(units, policy.earnPointsPerUnit());
    }

    private long clampRedemption(CustomerPricingContext customer, BigDecimal afterDiscountMinor,
                                 Currency currency) {
        /*
         * Points buy nothing outside the currency they are denominated in.
         *
         * `pointValueMinor` is 100 minor units — one rupee. Spending a rupee-denominated
         * balance against a dollar order values each point at a dollar instead, which on a
         * shared balance is a straight multiplier on the FX rate. Refusing is the only
         * safe answer here: the alternative is converting at a rate this system has no
         * source for and does not want one.
         */
        if (!policy.earnsLoyalty(currency)) {
            return 0L;
        }
        long requested = Math.min(customer.requestedPoints(), customer.walletPoints());
        if (requested <= 0) {
            return 0L;
        }
        BigDecimal capMinor = afterDiscountMinor
                .multiply(BigDecimal.valueOf(policy.maxWalletRedemptionBps()), MC)
                .divide(BPS, MC)
                .setScale(0, RoundingMode.DOWN);
        long capPoints = capMinor.longValueExact() / policy.pointValueMinor();
        return Math.max(0L, Math.min(requested, capPoints));
    }

    private BigDecimal gatewayFee(BigDecimal net) {
        if (policy.gatewayFeeMode() == GatewayFeeMode.ABSORBED || policy.gatewayFeeBps() == 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal rate = BigDecimal.valueOf(policy.gatewayFeeBps()).divide(BPS, MC);
        if (policy.gatewayFeeMode() == GatewayFeeMode.PASS_THROUGH) {
            return net.multiply(rate, MC);
        }
        // GROSS_UP: charge X such that X − X·rate == net
        return net.divide(BigDecimal.ONE.subtract(rate, MC), MC).subtract(net, MC);
    }

    private static long round(BigDecimal minor) {
        return minor.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private static String pct(int bps) {
        BigDecimal p = BigDecimal.valueOf(bps).divide(BigDecimal.valueOf(100));
        return p.stripTrailingZeros().toPlainString() + "%";
    }

    private static String baseLabel(RateCard card, BigDecimal qty) {
        if (card.sku().unit() == PriceUnit.PER_MILLION) {
            return card.sku().displayName() + " — " + qty.stripTrailingZeros().toPlainString()
                    + "M (" + card.platform().displayName() + ")";
        }
        // The rate card's own label, not the variant. A receipt reading
        // "Rivals Boosting — DIV_1_TO_ELITE" tells the customer we shipped a database
        // column to them by accident.
        return card.sku().displayName() + " — " + card.describe();
    }

    /** Seam so tests get deterministic ids and production gets unguessable ones. */
    @FunctionalInterface
    public interface QuoteIdGenerator {
        String next();
    }
}
