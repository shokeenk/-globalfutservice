package com.globalfutservice.domain.payments;

/**
 * Where a manual payment claim has got to.
 *
 * <p>Distinct from {@link PaymentStatus}, which describes what a gateway told us. This
 * describes what a customer told us and whether anybody has checked it yet, and the two
 * must not be conflated: a CAPTURED payment is money we have, a SUBMITTED claim is a
 * sentence somebody typed.
 */
public enum ClaimStatus {

    /** The customer says they paid. Nobody has looked. Worth nothing on its own. */
    SUBMITTED,

    /** An operator found the money and released the order. */
    VERIFIED,

    /** An operator could not find the money. The customer may submit a new claim. */
    REJECTED;

    public boolean isReviewed() {
        return this != SUBMITTED;
    }
}
