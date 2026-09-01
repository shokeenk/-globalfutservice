package com.globalfutservice.domain.crypto;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * Envelope encryption for the one piece of data in this system that would be a genuine
 * incident if it leaked: a customer's EA sign-in, held only for as long as a comfort-trade
 * order is being fulfilled.
 *
 * <p>Design:
 * <ul>
 *   <li>A fresh random 256-bit <b>data key</b> per order. One compromised record cannot
 *       decrypt any other record.</li>
 *   <li>Payload sealed with AES-256-GCM — authenticated encryption, so tampering with the
 *       ciphertext in the database is detected at decrypt rather than silently returning
 *       garbage.</li>
 *   <li>The data key is itself sealed with a <b>master key</b> that lives only in the
 *       environment (or a KMS). Rotating the master re-wraps data keys without touching
 *       ciphertext.</li>
 *   <li>A random 96-bit IV per operation, never reused. GCM with a repeated IV under the
 *       same key is catastrophic, not merely weak.</li>
 * </ul>
 *
 * <p>The master key never appears in {@code application.yml}, in a constant, or in git.
 */
public final class EnvelopeCipher {

    private static final String TRANSFORM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int DEK_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKey masterKey;

    /**
     * @param masterKeyBytes exactly 32 bytes of high-entropy key material, supplied from
     *                       the environment. Generate with
     *                       {@code openssl rand -base64 32}.
     */
    public EnvelopeCipher(byte[] masterKeyBytes) {
        if (masterKeyBytes == null || masterKeyBytes.length != DEK_BYTES) {
            throw new IllegalArgumentException(
                    "Master key must be exactly 256 bits. Generate one with: openssl rand -base64 32");
        }
        this.masterKey = new SecretKeySpec(masterKeyBytes.clone(), "AES");
    }

    /** Ciphertext plus the wrapped data key needed to open it. */
    public record Sealed(byte[] ciphertext, byte[] iv, byte[] wrappedDek) {
        public Sealed {
            ciphertext = ciphertext.clone();
            iv = iv.clone();
            wrappedDek = wrappedDek.clone();
        }

        @Override
        public String toString() {
            // Never let a sealed blob render itself into a log line.
            return "Sealed[" + ciphertext.length + " bytes]";
        }
    }

    public Sealed seal(String plaintext) {
        byte[] dek = new byte[DEK_BYTES];
        RANDOM.nextBytes(dek);
        try {
            byte[] iv = randomIv();
            byte[] ciphertext = doCipher(
                    Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"), iv,
                    plaintext.getBytes(StandardCharsets.UTF_8));

            byte[] dekIv = randomIv();
            byte[] wrapped = doCipher(Cipher.ENCRYPT_MODE, masterKey, dekIv, dek);
            byte[] wrappedDek = concat(dekIv, wrapped);

            return new Sealed(ciphertext, iv, wrappedDek);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    public String open(Sealed sealed) {
        byte[] dekIv = Arrays.copyOfRange(sealed.wrappedDek(), 0, IV_BYTES);
        byte[] wrapped = Arrays.copyOfRange(sealed.wrappedDek(), IV_BYTES, sealed.wrappedDek().length);
        byte[] dek = doCipher(Cipher.DECRYPT_MODE, masterKey, dekIv, wrapped);
        try {
            byte[] plain = doCipher(
                    Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"), sealed.iv(), sealed.ciphertext());
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(dek, (byte) 0);
        }
    }

    private static byte[] randomIv() {
        byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(iv);
        return iv;
    }

    private static byte[] doCipher(int mode, SecretKey key, byte[] iv, byte[] input) {
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORM);
            cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
            return cipher.doFinal(input);
        } catch (Exception e) {
            // Deliberately opaque: the caller must not learn whether the tag failed,
            // the key was wrong, or the payload was malformed.
            throw new IllegalStateException("Cipher operation failed", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
