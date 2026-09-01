package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.money.Money;

import java.time.Instant;
import java.util.Locale;

/**
 * A discount code, as the pricing engine sees it.
 *
 * <p>Framework-free and self-validating. The ceiling is enforced <b>here</b>, in the
 * constructor, rather than only in the admin form — a form is one bad request away from
 * being bypassed, and a coupon is a direct instruction to charge somebody less money.
 * The same ceiling is repeated as a {@code CHECK} constraint on the table, so a row
 * written by hand in psql cannot exceed it either. Three layers, because the failure mode
 * is silently discounting every order by 90%.
 *
 * @param code               normalised to upper case; what the customer typed
 * @param discountBps        basis points off the subtotal, never above {@link #MAX_DISCOUNT_BPS}
 * @param minOrderMinor      minimum subtotal for the code to apply, or 0 for none
 * @param expiresAt          when it stops working, or null for no expiry
 */
public record Coupon(
        String code,
        int discountBps,
        long minOrderMinor,
        Instant expiresAt) {

    /**
     * The hard ceiling: 20%.
     *
     * <p>Not configurable on purpose. A configurable maximum is a maximum somebody raises
     * at 2am to honour a promise made in a Discord message, and the whole value of a cap
     * is that it holds when it is inconvenient. Raising it is a code change and a review.
     */
    public static final int MAX_DISCOUNT_BPS = 2_000;

    /** Below this a code is pointless and almost always a typo for something larger. */
    public static final int MIN_DISCOUNT_BPS = 1;

    public Coupon {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("A coupon needs a code");
        }
        code = normalise(code);
        if (code.length() < 3 || code.length() > 32) {
            throw new IllegalArgumentException("A coupon code is 3 to 32 characters");
        }
        if (!code.matches("[A-Z0-9_-]+")) {
            throw new IllegalArgumentException(
                    "A coupon code may contain only letters, numbers, hyphens and underscores");
        }
        if (discountBps < MIN_DISCOUNT_BPS) {
            throw new IllegalArgumentException("A coupon must discount something");
        }
        if (discountBps > MAX_DISCOUNT_BPS) {
            throw new IllegalArgumentException(
                    "A coupon cannot exceed " + percent(MAX_DISCOUNT_BPS));
        }
        if (minOrderMinor < 0) {
            throw new IllegalArgumentException("A minimum order cannot be negative");
        }
    }

    /**
     * Upper case, trimmed.
     *
     * <p>Codes are read off a stream overlay and typed by hand, so "vinu20", "Vinu20" and
     * " VINU20 " are all the same code. Normalising on the way in — and storing the
     * normalised form — means the uniqueness index actually prevents duplicates, which a
     * case-sensitive column would not.
     */
    public static String normalise(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** Whether this code still applies at the given moment. */
    public boolean isLiveAt(Instant now) {
        return expiresAt == null || now.isBefore(expiresAt);
    }

    /** Whether an order of this size clears the code's minimum. */
    public boolean acceptsOrderOf(Money subtotal) {
        return subtotal.minor() >= minOrderMinor;
    }

    public String describe() {
        return code + " (" + percent(discountBps) + " off)";
    }

    private static String percent(int bps) {
        java.math.BigDecimal p = java.math.BigDecimal.valueOf(bps)
                .divide(java.math.BigDecimal.valueOf(100));
        return p.stripTrailingZeros().toPlainString() + "%";
    }
}
