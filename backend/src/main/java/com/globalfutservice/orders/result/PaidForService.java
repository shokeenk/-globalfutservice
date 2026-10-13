package com.globalfutservice.orders.result;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.domain.pricing.LineCode;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.web.OrderMapper;

import java.time.Instant;
import java.util.Set;

/**
 * What an order's frozen breakdown says it paid for the service, read once for a result.
 *
 * @param servicePaidMinor every line except the payment fee: the list price less the
 *                         coupon, tier discount and points. Store credit counts as paid --
 *                         it is money the customer held, not a discount -- so it is not
 *                         taken off here
 * @param pricedAt         when the order was priced: the quote's issue time, else when it
 *                         was placed
 */
public record PaidForService(long servicePaidMinor, Instant pricedAt) {

    /** Lines that are not the service: the payment fee (2.5% or a Payop method's) and store credit. */
    private static final Set<String> NOT_THE_SERVICE = Set.of(
            LineCode.GATEWAY_FEE.name(), OrderMapper.PAYMENT_FEE, LineCode.STORE_CREDIT.name());

    public static PaidForService of(OrderEntity order, ObjectMapper mapper) {
        Instant pricedAt = order.getCreatedAt();
        long paid = 0;
        boolean any = false;
        try {
            JsonNode quote = mapper.readTree(order.getPriceBreakdown());
            pricedAt = issuedAt(quote.get("issuedAt"), pricedAt);
            for (JsonNode line : quote.path("lines")) {
                if (!NOT_THE_SERVICE.contains(line.path("code").asText())) {
                    paid += line.path("amountMinor").asLong();
                    any = true;
                }
            }
        } catch (Exception e) {
            any = false;
        }
        if (!any) {
            // No breakdown to read: what was charged, the fee and all, is the most it could be.
            paid = order.getTotalMinor() + order.getStoreCreditMinor();
        }
        return new PaidForService(Math.max(0L, paid), pricedAt);
    }

    private static Instant issuedAt(JsonNode issued, Instant fallback) {
        try {
            if (issued != null && issued.isNumber()) {
                // Written as epoch seconds by a mapper that writes dates as numbers.
                java.math.BigDecimal seconds = issued.decimalValue();
                return Instant.ofEpochSecond(seconds.longValue(),
                        seconds.remainder(java.math.BigDecimal.ONE).movePointRight(9).intValue());
            }
            if (issued != null && issued.isTextual()) {
                return Instant.parse(issued.asText());
            }
        } catch (RuntimeException e) {
            // Unreadable: when the order was placed is minutes from it at most.
        }
        return fallback;
    }
}
