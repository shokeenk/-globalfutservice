package com.globalfutservice.notify;

/**
 * Something about taking a payment needs a person: a Payop payment that could not be
 * confirmed, one that arrived for an order already paid, or a Payop credential about to
 * stop working.
 *
 * <p>For staff channels only. Never carries payer details, a signature, a key or a response
 * body: {@code detail} is written by us, and {@code code} is a bare identifier such as
 * {@code AMOUNT_MISMATCH} or {@code TOKEN_EXPIRING}.
 *
 * @param publicRef the order it is about, or null when it is about the integration itself
 * @param headline  one line, e.g. "Duplicate payment, refund needed"
 * @param detail    what happened and what to do, for the admin reading it
 */
public record PaymentAlert(String publicRef, String headline, String detail, String code,
                           String adminDeepLink) {

    /** What the alert is filed under: the order, or Payop itself. */
    public String subject() {
        return publicRef == null || publicRef.isBlank() ? "Payop" : publicRef;
    }
}
