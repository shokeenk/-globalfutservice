package com.globalfutservice.payments;

/**
 * How money went back to a customer: the three rails it arrives on by hand, and Payop for
 * an order paid by card through International (Payop).
 */
public enum RefundMethod {

    /** The reference is the UTR of the transfer back. */
    UPI("UPI"),
    /** The reference is the PayPal refund's transaction id. */
    PAYPAL("PayPal"),
    /** The reference is the transaction hash. */
    CRYPTO("USDT (TRON)"),
    /** Refunded in Payop. The reference is the Payop refund ID. */
    PAYOP("Payop");

    private final String label;

    RefundMethod(String label) {
        this.label = label;
    }

    /** As staff and the customer's timeline name it. */
    public String label() {
        return label;
    }
}
