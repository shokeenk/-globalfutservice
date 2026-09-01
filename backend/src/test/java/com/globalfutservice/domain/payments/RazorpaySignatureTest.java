package com.globalfutservice.domain.payments;

import com.globalfutservice.domain.crypto.Hmac;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Webhook and checkout-callback verification.
 *
 * <p>These are the only things standing between the order table and a forged "payment
 * successful". The fail-closed cases matter as much as the happy path: a verifier that
 * returns true when the secret is unset turns a public endpoint into a way to mark any
 * order paid.
 */
class RazorpaySignatureTest {

    private static final String WEBHOOK_SECRET = "wh_secret_value";
    private static final String BODY = """
            {"event":"payment.captured","payload":{"payment":{"entity":\
            {"id":"pay_123","order_id":"order_abc","amount":263681}}}}""";

    @Test
    @DisplayName("a genuine signature is accepted")
    void valid_signature() {
        String signature = Hmac.hexSha256(WEBHOOK_SECRET, BODY);
        assertThat(RazorpaySignature.verifyWebhook(BODY, signature, WEBHOOK_SECRET)).isTrue();
    }

    @Test
    @DisplayName("changing so much as the amount invalidates it")
    void tampered_body() {
        String signature = Hmac.hexSha256(WEBHOOK_SECRET, BODY);
        String cheaper = BODY.replace("263681", "100");
        assertThat(RazorpaySignature.verifyWebhook(cheaper, signature, WEBHOOK_SECRET)).isFalse();
    }

    @Test
    @DisplayName("verification fails closed on missing input")
    void fails_closed() {
        String signature = Hmac.hexSha256(WEBHOOK_SECRET, BODY);
        assertThat(RazorpaySignature.verifyWebhook(BODY, signature, "")).isFalse();
        assertThat(RazorpaySignature.verifyWebhook(BODY, signature, null)).isFalse();
        assertThat(RazorpaySignature.verifyWebhook(BODY, null, WEBHOOK_SECRET)).isFalse();
        assertThat(RazorpaySignature.verifyWebhook(null, signature, WEBHOOK_SECRET)).isFalse();
        assertThat(RazorpaySignature.verifyWebhook(BODY, "", WEBHOOK_SECRET)).isFalse();
    }

    @Test
    @DisplayName("a signature from a different secret is rejected")
    void wrong_secret() {
        assertThat(RazorpaySignature.verifyWebhook(
                BODY, Hmac.hexSha256("someone_elses_secret", BODY), WEBHOOK_SECRET)).isFalse();
    }

    @Test
    @DisplayName("the checkout callback binds order and payment together")
    void checkout_callback() {
        String keySecret = "key_secret_value";
        String signature = Hmac.hexSha256(keySecret, "order_ABC|pay_XYZ");

        assertThat(RazorpaySignature.verifyCheckoutCallback(
                "order_ABC", "pay_XYZ", signature, keySecret)).isTrue();
        assertThat(RazorpaySignature.verifyCheckoutCallback(
                "pay_XYZ", "order_ABC", signature, keySecret))
                .as("swapping the identifiers must not verify")
                .isFalse();
    }

    @Test
    @DisplayName("HMAC matches the published RFC-style vector")
    void known_vector() {
        assertThat(Hmac.hexSha256("key", "The quick brown fox jumps over the lazy dog"))
                .isEqualTo("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8");
    }
}
