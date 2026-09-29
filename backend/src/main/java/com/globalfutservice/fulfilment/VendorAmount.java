package com.globalfutservice.fulfilment;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.orders.OrderEntity;

/**
 * The number of coins we ask the vendor for, and the proof that it is the number paid for.
 *
 * <p>Orders store coins in millions ({@code 0.10} is 100K); the vendor takes whole
 * thousands ({@code 100}). The conversion is exact or it is refused: rounding would mean
 * sending a customer more or fewer coins than they bought, and the vendor charges our
 * senders for every one of them.
 *
 * <p>The amount comes from the order's own quantity and must match the quantity in the
 * signed quote the customer accepted and paid for. A disagreement between the two means
 * the order was changed after checkout, and nothing is sent.
 */
public final class VendorAmount {

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    private VendorAmount() {
    }

    /** Coins in millions to whole thousands, exactly: 0.5 is 500, 0.0005 is refused. */
    public static long thousandsExact(BigDecimal millions) {
        if (millions == null || millions.signum() <= 0) {
            throw new InvalidAmountException("The order has no positive coin amount.");
        }
        try {
            return millions.multiply(THOUSAND).longValueExact();
        } catch (ArithmeticException e) {
            throw new InvalidAmountException("The order's amount, " + millions.toPlainString()
                    + "M, is not a whole number of thousands of coins, so it cannot be sent exactly.");
        }
    }

    /**
     * The amount to send for this order, in thousands, checked against the paid quote.
     *
     * @throws InvalidAmountException if the amount is inexact, missing, or differs from
     *                                the quote the customer paid for
     */
    public static long forOrder(OrderEntity order, ObjectMapper mapper) {
        long k = thousandsExact(order.getQuantity());
        BigDecimal quoted = quotedQuantity(order, mapper);
        if (quoted == null) {
            throw new InvalidAmountException("The order's quote does not record a coin amount, so "
                    + "there is nothing to check the amount against.");
        }
        if (quoted.compareTo(order.getQuantity()) != 0) {
            throw new InvalidAmountException("The order's amount (" + order.getQuantity().toPlainString()
                    + "M) differs from the quote the customer paid for (" + quoted.toPlainString() + "M).");
        }
        return k;
    }

    private static BigDecimal quotedQuantity(OrderEntity order, ObjectMapper mapper) {
        String breakdown = order.getPriceBreakdown();
        if (breakdown == null || breakdown.isBlank()) return null;
        try {
            JsonNode q = mapper.readTree(breakdown).get("quantity");
            return q == null || !q.isNumber() ? null : q.decimalValue();
        } catch (Exception e) {
            return null;
        }
    }

    /** The amount cannot be sent. Its message is safe to show an admin. */
    public static class InvalidAmountException extends RuntimeException {
        public InvalidAmountException(String message) {
            super(message);
        }
    }
}
