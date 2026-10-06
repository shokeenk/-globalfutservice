package com.globalfutservice.payments.payop;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.crypto.Hmac;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.web.ApiExceptions;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;

/**
 * The price of one Payop method for one order, sealed so it can go to the browser and come
 * back as the only thing a payment is started from.
 *
 * <p>Resuming a payment never sends an amount. The server prices each method, hands the
 * customer a token per method, and the token is what comes back: it names the order, the
 * method, the country and the total, and a {@link #LIFETIME} after it was issued it is no
 * longer accepted. Starting the payment prices the method again; if the price has moved
 * since the token was issued, nothing is created and the customer is shown the new one.
 *
 * <p>Signed like the checkout's price quotes ({@link com.globalfutservice.domain.pricing.QuoteSigner}):
 * HMAC-SHA256 under the quote signing secret, compared in constant time. Its first field
 * names its purpose, so neither can be passed off as the other.
 */
@Component
public class PayopStartToken {

    /** Long enough to read the methods and choose one; short enough that a rate seldom moves under it. */
    public static final Duration LIFETIME = Duration.ofMinutes(10);

    private static final String PURPOSE = "payop-start";
    private static final char SEP = '|';

    private final String secret;
    private final Clock clock;

    public PayopStartToken(AppProperties props, Clock clock) {
        this(props.security().quoteSigningSecret(), clock);
    }

    PayopStartToken(String secret, Clock clock) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("The quote signing secret must be at least 32 characters.");
        }
        this.secret = secret;
        this.clock = clock;
    }

    /** What a valid token says. */
    public record Claims(long methodId, String country, Currency currency, long totalMinor, Instant expiresAt) {
    }

    public String issue(OrderEntity order, long methodId, String country, long totalMinor) {
        Instant expiresAt = clock.instant().plus(LIFETIME);
        String payload = PURPOSE + SEP + "v1" + SEP + order.getId() + SEP + order.getPublicRef() + SEP + methodId
                + SEP + country + SEP + order.getCurrency().name() + SEP + totalMinor + SEP + expiresAt.toEpochMilli();
        return encode(payload) + "." + Hmac.base64UrlSha256(secret, payload);
    }

    /**
     * The token's claims, if it was issued here for this order and has not expired.
     *
     * @throws ApiExceptions.BadRequestException  a token that was not issued for this order, or was altered
     * @throws ApiExceptions.ConflictException    {@code payment_token_expired}: issued for this order, but too long ago
     */
    public Claims verify(OrderEntity order, String token) {
        String payload = payloadOf(token);
        String[] f = payload.split("\\" + SEP, -1);
        if (f.length != 9 || !PURPOSE.equals(f[0]) || !"v1".equals(f[1])
                || !String.valueOf(order.getId()).equals(f[2]) || !order.getPublicRef().equals(f[3])
                || !order.getCurrency().name().equals(f[6])) {
            throw invalid();
        }
        Claims claims;
        try {
            claims = new Claims(Long.parseLong(f[4]), f[5], Currency.valueOf(f[6]), Long.parseLong(f[7]),
                    Instant.ofEpochMilli(Long.parseLong(f[8])));
        } catch (RuntimeException e) {
            throw invalid();
        }
        if (!clock.instant().isBefore(claims.expiresAt())) {
            throw new ApiExceptions.ConflictException("payment_token_expired",
                    "Prices are refreshed every few minutes. Choose your payment method again.");
        }
        return claims;
    }

    /** The payload, once its signature has checked out. */
    private String payloadOf(String token) {
        if (token == null) {
            throw invalid();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            throw invalid();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
        if (!Hmac.constantTimeEquals(Hmac.base64UrlSha256(secret, payload), token.substring(dot + 1))) {
            throw invalid();
        }
        return payload;
    }

    private static String encode(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private static ApiExceptions.BadRequestException invalid() {
        return new ApiExceptions.BadRequestException("payment_token_invalid",
                "Choose your payment method again.");
    }
}
