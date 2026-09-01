package com.globalfutservice.domain.pricing;

/** How the payment-processing cost is treated. */
public enum GatewayFeeMode {
    /** Merchant eats it. No line item appears. Highest conversion, lowest margin. */
    ABSORBED,
    /** fee = net × rate. The common choice, and what the client asked for at 2.5%. */
    PASS_THROUGH,
    /**
     * fee = net ÷ (1 − rate) − net, so that after the processor takes its cut the
     * merchant nets exactly the quoted amount. Mathematically correct, marginally dearer.
     */
    GROSS_UP
}
