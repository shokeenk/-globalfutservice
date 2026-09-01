package com.globalfutservice.domain.pricing;

/**
 * Every component of a price, in the order it is applied.
 *
 * <p>The order is fixed and load-bearing. Whether the gateway fee lands before or after
 * a discount is a real money question that has to be decided once, written down, and
 * pinned by a test — not rediscovered every time someone edits the engine.
 */
public enum LineCode {
    /** unit price × quantity */
    BASE,
    /** EA transfer-market tax, passed through where auction-house delivery is used */
    MARKET_TAX,
    /** creator-code discount on a customer's first order */
    REFERRAL_DISCOUNT,
    /**
     * a coupon code the customer typed at checkout (negative)
     *
     * <p>Mutually exclusive with REFERRAL_DISCOUNT — see {@code PricingEngine}. Both are
     * promotional discounts on the same subtotal, and letting them stack turns two
     * separately reasonable offers into one nobody signed off.
     */
    COUPON_DISCOUNT,
    /**
     * automatic loyalty-tier discount, earned by lifetime spend (negative)
     *
     * <p>Sits alongside REFERRAL_DISCOUNT rather than compounding on it: both are computed
     * against the same subtotal. In practice they almost never co-occur — a referral
     * discount is first-order-only and a tier above BRONZE requires accumulated spend — but
     * "almost never" is not "never", and an order that hits both must not have the second
     * discount silently applied to the first one's output.
     */
    TIER_DISCOUNT,
    /** loyalty points spent on this order (negative) */
    WALLET_REDEMPTION,
    /** payment-processing fee */
    GATEWAY_FEE
}
