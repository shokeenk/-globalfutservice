package com.globalfutservice.domain.catalog;

import java.math.BigDecimal;

/**
 * A coin quantity, written the way a player says it.
 *
 * <p>Quantities are carried in millions because that is the unit the rate card prices in,
 * and that unit stops being readable below one million: an order for a hundred thousand
 * coins is "100K", never "0.1M". This matters more than it looks, because the string ends
 * up on the customer's receipt line and inside the frozen order label -- the one an
 * operator reads in the queue and a customer reads on an invoice months later.
 *
 * <p>Shared rather than written twice. The pricing engine builds the quote line and
 * {@code OrderService} freezes the order label, and the two disagreeing about how to spell
 * the same number is exactly the kind of difference that turns into a support ticket.
 * There is a matching {@code coinsShort} on the storefront, for the same reason.
 */
public final class CoinAmount {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private CoinAmount() {
    }

    /**
     * @param millions the quantity as the rate card carries it
     * @return "10K", "250K", "1M", "3.5M"
     */
    public static String describe(BigDecimal millions) {
        if (millions == null) {
            return "";
        }
        if (millions.compareTo(BigDecimal.ONE) >= 0) {
            return millions.stripTrailingZeros().toPlainString() + "M";
        }
        return millions.multiply(THOUSAND).stripTrailingZeros().toPlainString() + "K";
    }
}
