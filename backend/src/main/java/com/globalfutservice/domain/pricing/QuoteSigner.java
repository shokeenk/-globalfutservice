package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.crypto.Hmac;

import java.time.Instant;

/**
 * Seals a {@link Quote} so it can travel to the browser and come back trustworthy.
 *
 * <p>The alternative — persisting every quote and looking it up by id — means a row per
 * slider drag. A signed quote is stateless, self-expiring, and cannot be edited in
 * DevTools. Replay is handled one layer up by a unique index on {@code orders.quote_id}:
 * a given quote can become at most one order, ever.
 *
 * <p>The signature covers <b>every field that moves money</b>, plus a binding to the
 * customer it was issued to, so a quote minted for a Diamond-balance account cannot be
 * lifted and replayed by a guest.
 */
public final class QuoteSigner {

    private static final char SEP = '|';
    private final String secret;

    public QuoteSigner(String secret) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException(
                    "Quote signing secret must be at least 32 characters. "
                            + "Generate one with: openssl rand -base64 48");
        }
        this.secret = secret;
    }

    public String sign(Quote quote, String customerBinding) {
        return Hmac.base64UrlSha256(secret, canonical(quote, customerBinding));
    }

    /**
     * @return true only if the signature matches AND the quote has not expired
     */
    public boolean verify(Quote quote, String customerBinding, String signature, Instant now) {
        if (quote.isExpiredAt(now)) {
            return false;
        }
        return Hmac.constantTimeEquals(sign(quote, customerBinding), signature);
    }

    /**
     * Canonical form. Field order and separators are part of the protocol: changing them
     * invalidates every quote in flight, which is acceptable, but changing them
     * <i>accidentally</i> — by reordering a record's components, say — would be a silent
     * outage, so this is written out longhand rather than derived by reflection.
     */
    private String canonical(Quote q, String customerBinding) {
        StringBuilder sb = new StringBuilder(256);
        sb.append("v1").append(SEP)
                .append(q.quoteId()).append(SEP)
                .append(q.season()).append(SEP)
                .append(q.sku().name()).append(SEP)
                .append(q.platform() == null ? "-" : q.platform().name()).append(SEP)
                .append(q.variant() == null ? "-" : q.variant()).append(SEP)
                .append(q.quantity().stripTrailingZeros().toPlainString()).append(SEP)
                .append(q.currency().name()).append(SEP)
                .append(q.total().minor()).append(SEP)
                .append(q.pointsRedeemed()).append(SEP)
                .append(q.pointsEarned()).append(SEP)
                .append(q.referralCode() == null ? "-" : q.referralCode()).append(SEP)
                .append(q.couponCode() == null ? "-" : q.couponCode()).append(SEP)
                .append(q.expiresAt().toEpochMilli()).append(SEP)
                .append(customerBinding == null ? "guest" : customerBinding);
        return sb.toString();
    }
}
