package com.globalfutservice.payments.payop;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/*
 * Payop's documentation describes the signature one way in three places: the PHP script in
 * signatureGenerator.md, the one in s2s.md, and its written form, amount:currency:id:secretKey.
 * Its two "verified signature examples" (s2s.md) cannot be produced by that method -- nor by
 * any reordering, separator, HMAC or other hash we tried -- so they are not used as the test.
 * The expected values below were computed independently, with Python's hashlib, on the
 * documented string. The first real invoice settles it: Payop answers "Wrong signature".
 */
class PayopSignatureTest {

    @Test
    @DisplayName("sha256 of amount:currency:orderId:secretKey, as Payop documents it")
    void documentedMethod() {
        // hashlib.sha256(b"1.2000:USD:Test-Order-354:secretkey1").hexdigest()
        assertThat(PayopSignature.of("1.2000", "USD", "Test-Order-354", "secretkey1"))
                .isEqualTo("fc8175a961be3680b2cef8f25f8e4128b229cd7cae43742f1c5548184247b8cc");
        // hashlib.sha256(b"104.48:EUR:GFS-26-AB12CD34:k").hexdigest()
        assertThat(PayopSignature.of("104.48", "EUR", "GFS-26-AB12CD34", "k"))
                .isEqualTo("a8c24331a3521fd18979fcdb63a3a60f1891b30293bafe79120f96ebecf39b9b");
    }

    @Test
    @DisplayName("the amount is signed exactly as sent: 2 and 2.00 sign differently")
    void amountExactly() {
        assertThat(PayopSignature.of("2", "EUR", "GFS-26-AAAAAAAA", "k"))
                .isNotEqualTo(PayopSignature.of("2.00", "EUR", "GFS-26-AAAAAAAA", "k"));
    }

    @Test
    @DisplayName("lower-case hex, 64 characters")
    void format() {
        assertThat(PayopSignature.of("1.00", "USD", "x", "y")).matches("[0-9a-f]{64}");
    }
}
