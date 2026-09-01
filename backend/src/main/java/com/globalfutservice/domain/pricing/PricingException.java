package com.globalfutservice.domain.pricing;

/**
 * A pricing input the customer can fix — quantity out of range, SKU not for sale.
 * The message is written to be shown verbatim in the UI, so it must never contain
 * internal identifiers or stack context.
 */
public class PricingException extends RuntimeException {
    public PricingException(String message) {
        super(message);
    }
}
