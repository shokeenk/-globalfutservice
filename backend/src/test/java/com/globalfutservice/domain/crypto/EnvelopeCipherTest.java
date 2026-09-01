package com.globalfutservice.domain.crypto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The credential vault's cryptography.
 *
 * <p>The interesting assertions are the negative ones: that a tampered ciphertext is
 * rejected rather than silently decrypted to garbage, that a wrong master key cannot
 * unwrap, and that two encryptions of the same password produce different bytes — because
 * a deterministic ciphertext lets an observer match records without decrypting anything.
 */
class EnvelopeCipherTest {

    private static final String PAYLOAD =
            "{\"eaEmail\":\"player@example.com\",\"eaPassword\":\"correct horse battery staple\"}";

    private byte[] master;
    private EnvelopeCipher cipher;

    @BeforeEach
    void setUp() {
        master = new byte[32];
        new SecureRandom().nextBytes(master);
        cipher = new EnvelopeCipher(master);
    }

    @Test
    @DisplayName("round-trips")
    void round_trip() {
        EnvelopeCipher.Sealed sealed = cipher.seal(PAYLOAD);
        assertThat(cipher.open(sealed)).isEqualTo(PAYLOAD);
    }

    @Test
    @DisplayName("the password never appears in the stored bytes")
    void ciphertext_hides_plaintext() {
        EnvelopeCipher.Sealed sealed = cipher.seal(PAYLOAD);
        String asText = new String(sealed.ciphertext(), StandardCharsets.UTF_8);
        assertThat(asText).doesNotContain("correct horse");
    }

    @Test
    @DisplayName("each record gets its own data key and IV")
    void per_record_keys() {
        EnvelopeCipher.Sealed a = cipher.seal(PAYLOAD);
        EnvelopeCipher.Sealed b = cipher.seal(PAYLOAD);

        assertThat(Arrays.equals(a.wrappedDek(), b.wrappedDek())).isFalse();
        assertThat(Arrays.equals(a.iv(), b.iv())).isFalse();
        assertThat(Arrays.equals(a.ciphertext(), b.ciphertext()))
                .as("identical plaintexts must not produce identical ciphertexts, or an "
                        + "observer could match records without decrypting them")
                .isFalse();
    }

    @Test
    @DisplayName("a tampered ciphertext fails the authentication tag")
    void tampering_detected() {
        EnvelopeCipher.Sealed sealed = cipher.seal(PAYLOAD);
        byte[] corrupted = sealed.ciphertext().clone();
        corrupted[corrupted.length / 2] ^= 0x01;

        assertThatThrownBy(() -> cipher.open(
                new EnvelopeCipher.Sealed(corrupted, sealed.iv(), sealed.wrappedDek())))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a different master key cannot unwrap the data key")
    void wrong_master_key() {
        EnvelopeCipher.Sealed sealed = cipher.seal(PAYLOAD);

        byte[] other = new byte[32];
        new SecureRandom().nextBytes(other);
        EnvelopeCipher wrong = new EnvelopeCipher(other);

        assertThatThrownBy(() -> wrong.open(sealed)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a weak or missing master key stops the application from starting")
    void key_length_enforced() {
        assertThatThrownBy(() -> new EnvelopeCipher(new byte[16]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("256 bits");
        assertThatThrownBy(() -> new EnvelopeCipher(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a sealed blob will not print itself into a log line")
    void no_accidental_disclosure() {
        assertThat(cipher.seal(PAYLOAD).toString())
                .startsWith("Sealed[")
                .doesNotContain("correct horse");
    }
}
