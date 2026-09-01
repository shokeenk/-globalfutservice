package com.globalfutservice.domain.payments;

import com.globalfutservice.domain.crypto.Hmac;

/**
 * The two Razorpay signature checks, both of which are the only thing standing between
 * the order table and a forged "payment successful".
 *
 * <p>Neither is optional and neither can be replaced by trusting the browser. The
 * checkout callback tells the UI what to render; the <b>webhook</b> is what moves money
 * in the database. A customer can close the tab, replay the redirect, or hand-craft the
 * success parameters — none of that reaches the ledger.
 */
public final class RazorpaySignature {

    private RazorpaySignature() {
    }

    /**
     * Verifies the {@code X-Razorpay-Signature} header on a webhook.
     *
     * <p><b>Must be computed over the exact raw bytes of the request body.</b> Parsing to
     * a DTO and re-serialising changes key order and whitespace, and the MAC will never
     * match — which is why the webhook controller reads the body as a String and defers
     * JSON parsing until after this returns true.
     *
     * @param rawBody       the untouched request body
     * @param headerValue   value of the {@code X-Razorpay-Signature} header
     * @param webhookSecret the secret configured in the Razorpay dashboard — a different
     *                      value from the API key secret
     */
    public static boolean verifyWebhook(String rawBody, String headerValue, String webhookSecret) {
        if (rawBody == null || headerValue == null || webhookSecret == null || webhookSecret.isBlank()) {
            return false;
        }
        String expected = Hmac.hexSha256(webhookSecret, rawBody);
        return Hmac.constantTimeEquals(expected, headerValue);
    }

    /**
     * Verifies the handler payload returned to the browser by Razorpay Checkout.
     *
     * <p>Use this to decide what the customer sees next — never to mark an order paid.
     *
     * @param keySecret the API key secret
     */
    public static boolean verifyCheckoutCallback(
            String razorpayOrderId, String razorpayPaymentId, String signature, String keySecret) {
        if (razorpayOrderId == null || razorpayPaymentId == null
                || signature == null || keySecret == null || keySecret.isBlank()) {
            return false;
        }
        String expected = Hmac.hexSha256(keySecret, razorpayOrderId + "|" + razorpayPaymentId);
        return Hmac.constantTimeEquals(expected, signature);
    }
}
