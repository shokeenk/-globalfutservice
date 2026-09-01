package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * A price the server stands behind, for a short while.
 *
 * <p>The client never computes a price and never sends one. It sends intent — SKU,
 * platform, quantity — and receives this. At order creation the quote is re-verified by
 * signature and by expiry, and the customer context is re-read from the database, so a
 * tampered or stale quote cannot become an order.
 *
 * <p>{@code lines} always sums exactly to {@code total}; see
 * {@link PricingEngine} for how the rounding residual is reconciled.
 */
public record Quote(
        String quoteId,
        String season,
        Sku sku,
        Platform platform,
        String variant,
        BigDecimal quantity,
        Currency currency,
        List<QuoteLine> lines,
        Money subtotal,
        Money total,
        long pointsRedeemed,
        long pointsEarned,
        String referralCode,
        /** The coupon actually applied, or null. Recorded so the order can redeem it. */
        String couponCode,
        Instant issuedAt,
        Instant expiresAt) {

    public Quote {
        lines = List.copyOf(lines);
    }

    public boolean isExpiredAt(Instant now) {
        return !now.isBefore(expiresAt);
    }

    /** Defensive check used by tests and by the order service before persisting. */
    public boolean linesReconcile() {
        long sum = 0L;
        for (QuoteLine line : lines) {
            sum = Math.addExact(sum, line.amount().minor());
        }
        return sum == total.minor();
    }
}
