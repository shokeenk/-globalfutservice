package com.globalfutservice.payments;

import com.globalfutservice.domain.money.Money;

/**
 * The narrow slice of a payment provider this application actually needs.
 *
 * <p>Kept as an interface for two reasons that are not "clean architecture for its own
 * sake": the test suite needs a gateway that does not make network calls, and the client
 * may well end up with a second provider — Razorpay's international support is a separate
 * activation, and a business selling to the diaspora audience this one targets tends to
 * outgrow a single processor.
 */
public interface PaymentGateway {

    /**
     * Registers an intent to charge, and returns the identifier the browser SDK needs.
     *
     * @param receipt  our own order reference, echoed back on the webhook
     * @param amount   the exact amount, in minor units
     */
    GatewayOrder createOrder(String receipt, Money amount, String customerEmail, String notes);

    /** Provider-side identifier plus the public key the checkout widget needs. */
    record GatewayOrder(String providerOrderId, String publicKey, long amountMinor, String currency) {
    }

    boolean isEnabled();
}
