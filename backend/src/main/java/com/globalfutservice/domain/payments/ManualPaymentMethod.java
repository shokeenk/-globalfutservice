package com.globalfutservice.domain.payments;

/**
 * How a customer paid, when they paid outside a gateway.
 *
 * <p>Three values rather than four, even though the storefront shows a different UPI
 * destination for coin orders than for boosting and coaching. Which account the money
 * went to is recorded on the claim as the destination string, because that is a fact
 * about one payment that has to stay true after the account changes. Splitting it into
 * two enum constants would freeze this month's banking arrangement into the domain
 * model and make every historic row a lie the first time a handle moves.
 */
public enum ManualPaymentMethod {

    /** Indian domestic transfer. The reference is a 12-digit UTR. */
    UPI("UTR"),

    /** International. The reference is a PayPal transaction id. */
    PAYPAL("transaction id"),

    /** USDT on TRON. The reference is a transaction hash. */
    CRYPTO("transaction hash");

    private final String referenceName;

    ManualPaymentMethod(String referenceName) {
        this.referenceName = referenceName;
    }

    /**
     * What this method's payers call their reference number. Used in operator-facing
     * text so a reviewer knows whether they are looking at something that should be
     * twelve digits or sixty-four hex characters.
     */
    public String referenceName() {
        return referenceName;
    }
}
