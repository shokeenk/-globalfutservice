package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.money.Money;

/**
 * One row of the price breakdown shown to the customer and frozen onto the order.
 *
 * @param code   machine-readable component identifier
 * @param label  customer-facing text, already localised by the caller
 * @param amount signed — discounts are negative
 */
public record QuoteLine(LineCode code, String label, Money amount) {
}
