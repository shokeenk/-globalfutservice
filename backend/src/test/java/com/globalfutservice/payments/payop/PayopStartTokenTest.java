package com.globalfutservice.payments.payop;

import com.globalfutservice.domain.crypto.Hmac;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The token a Payop payment is started from: the order, the method and the amount, sealed, briefly. */
class PayopStartTokenTest {

    private static final String SECRET = "a-quote-signing-secret-of-at-least-32-characters";

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-06T12:00:00Z"));
    private final PayopStartToken tokens = new PayopStartToken(SECRET, new Clock() {
        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now.get();
        }
    });

    private final OrderEntity order = PayopFakes.order(7, "GFS-26-EUR00001", Currency.EUR, OrderStatus.AWAITING_PAYMENT);
    private final OrderEntity other = PayopFakes.order(8, "GFS-26-EUR00002", Currency.EUR, OrderStatus.AWAITING_PAYMENT);

    private static void invalid(Runnable verify) {
        assertThatThrownBy(verify::run).isInstanceOfSatisfying(ApiExceptions.BadRequestException.class,
                e -> assertThat(e.code()).isEqualTo("payment_token_invalid"));
    }

    @Test
    @DisplayName("carries the method, the country and the amount the server priced, for this order")
    void roundTrip() {
        PayopStartToken.Claims claims = tokens.verify(order, tokens.issue(order, 381, "DE", 9407));
        assertThat(claims.methodId()).isEqualTo(381);
        assertThat(claims.country()).isEqualTo("DE");
        assertThat(claims.currency()).isEqualTo(Currency.EUR);
        assertThat(claims.totalMinor()).isEqualTo(9407);
        assertThat(claims.expiresAt()).isEqualTo(now.get().plus(PayopStartToken.LIFETIME));
    }

    @Test
    @DisplayName("tied to its order: another order's token is refused")
    void tiedToTheOrder() {
        String token = tokens.issue(order, 381, "DE", 9407);
        invalid(() -> tokens.verify(other, token));
    }

    @Test
    @DisplayName("an amount or method changed in the browser breaks the seal")
    void tamperProof() {
        String token = tokens.issue(order, 381, "DE", 9407);
        String payload = new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
                StandardCharsets.UTF_8);
        String signature = token.substring(token.indexOf('.') + 1);
        for (String forged : new String[] {payload.replace("|9407|", "|1|"), payload.replace("|381|", "|700001|")}) {
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(forged.getBytes(StandardCharsets.UTF_8));
            invalid(() -> tokens.verify(order, encoded + "." + signature));
        }
        invalid(() -> tokens.verify(order, token.substring(0, token.length() - 2) + "xx"));
        invalid(() -> tokens.verify(order, "not-a-token"));
        invalid(() -> tokens.verify(order, null));
    }

    @Test
    @DisplayName("signed for this purpose only: the same secret over another kind of message is not a token")
    void purposeBound() {
        String quoteLike = "v1|7|GFS-26-EUR00001|381|DE|EUR|9407|" + now.get().plusSeconds(600).toEpochMilli();
        String encoded = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(quoteLike.getBytes(StandardCharsets.UTF_8));
        invalid(() -> tokens.verify(order, encoded + "." + Hmac.base64UrlSha256(SECRET, quoteLike)));
    }

    @Test
    @DisplayName("expires quickly: ten minutes after it was issued it is refused, and the customer chooses again")
    void expires() {
        String token = tokens.issue(order, 381, "DE", 9407);
        now.set(now.get().plus(PayopStartToken.LIFETIME).minus(Duration.ofSeconds(1)));
        assertThat(tokens.verify(order, token).totalMinor()).isEqualTo(9407);
        now.set(now.get().plusSeconds(1));
        assertThatThrownBy(() -> tokens.verify(order, token))
                .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("payment_token_expired"));
        assertThat(PayopStartToken.LIFETIME).isLessThanOrEqualTo(Duration.ofMinutes(10));
    }
}
