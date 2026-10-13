package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GFS Store Credit in the price: after the coupon, tier discount and points, before the
 * payment fee -- so the fee is charged only on what is left to pay with money -- in the
 * order's own currency, earning no loyalty points, and signed with the quote.
 */
class StoreCreditPricingTest {

    private static final Instant NOW = Instant.parse("2026-10-13T10:00:00Z");
    private final PricingEngine engine = new PricingEngine(PricingPolicy.launchDefaults(),
            Clock.fixed(NOW, ZoneOffset.UTC), () -> "q_credit");

    private static RateCard champs15(Currency currency, long minor) {
        return new RateCard("FC26", Sku.BOOST_CHAMPS, null, "WINS_15", "15 wins · Elite I",
                Money.ofMinor(minor, currency), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    }

    private static CustomerPricingContext customer(long points, String coupon, int couponBps, long credit) {
        return new CustomerPricingContext(points, points, 0L, false, null, 0, coupon, couponBps, credit);
    }

    private static long line(Quote q, LineCode code) {
        return q.lines().stream().filter(l -> l.code() == code).mapToLong(l -> l.amount().minor()).sum();
    }

    @Test
    @DisplayName("with a coupon: the credit comes off after it, and the 2.5% is on what is left to pay")
    void afterCouponBeforeFee() {
        Quote q = engine.quote(champs15(Currency.INR, 400_000), BigDecimal.ONE, customer(0, "SAVE5", 500, 47_500));

        assertThat(line(q, LineCode.COUPON_DISCOUNT)).isEqualTo(-20_000);
        assertThat(line(q, LineCode.STORE_CREDIT)).isEqualTo(-47_500);
        // 2.5% of ₹3,325.00 -- never of the ₹475.00 the credit paid.
        assertThat(line(q, LineCode.GATEWAY_FEE)).isEqualTo(8_313);
        assertThat(q.total().minor()).isEqualTo(340_813);
        assertThat(q.storeCreditMinor()).isEqualTo(47_500);
        assertThat(q.linesReconcile()).isTrue();
        assertThat(q.lines().stream().map(QuoteLine::code).toList()).containsExactly(
                LineCode.BASE, LineCode.COUPON_DISCOUNT, LineCode.STORE_CREDIT, LineCode.GATEWAY_FEE);
    }

    @Test
    @DisplayName("with points too: coupon, points, then credit, then the fee")
    void withPoints() {
        Quote q = engine.quote(champs15(Currency.INR, 400_000), BigDecimal.ONE, customer(500, "SAVE5", 500, 10_000));

        assertThat(line(q, LineCode.WALLET_REDEMPTION)).isEqualTo(-50_000);
        assertThat(line(q, LineCode.STORE_CREDIT)).isEqualTo(-10_000);
        assertThat(line(q, LineCode.GATEWAY_FEE)).isEqualTo(8_000);
        assertThat(q.total().minor()).isEqualTo(328_000);
        assertThat(q.lines().stream().map(QuoteLine::code).toList()).containsExactly(LineCode.BASE,
                LineCode.COUPON_DISCOUNT, LineCode.WALLET_REDEMPTION, LineCode.STORE_CREDIT, LineCode.GATEWAY_FEE);
    }

    @Test
    @DisplayName("credit covering the whole order: nothing to pay, no fee, no points")
    void coversAll() {
        Quote q = engine.quote(champs15(Currency.INR, 400_000), BigDecimal.ONE, customer(0, null, 0, 500_000));

        assertThat(q.total().minor()).isZero();
        assertThat(q.storeCreditMinor()).as("never more than the order costs").isEqualTo(400_000);
        assertThat(line(q, LineCode.GATEWAY_FEE)).isZero();
        assertThat(q.lines().stream().map(QuoteLine::code)).doesNotContain(LineCode.GATEWAY_FEE);
        assertThat(q.pointsEarned()).isZero();
        assertThat(q.linesReconcile()).isTrue();
    }

    @Test
    @DisplayName("the part paid with credit earns no loyalty points")
    void noPointsOnCredit() {
        Quote without = engine.quote(champs15(Currency.INR, 400_000), BigDecimal.ONE, customer(0, null, 0, 0));
        Quote with = engine.quote(champs15(Currency.INR, 400_000), BigDecimal.ONE, customer(0, null, 0, 250_000));

        assertThat(without.pointsEarned()).as("₹4,100 paid: two ₹2,000 steps").isEqualTo(40);
        assertThat(with.total().minor()).isEqualTo(153_750);
        assertThat(with.pointsEarned()).as("₹1,537.50 paid in money: no full step").isZero();
    }

    @Test
    @DisplayName("in the order's own currency: a euro balance pays a euro order")
    void euros() {
        Quote q = engine.quote(champs15(Currency.EUR, 3_699), BigDecimal.ONE, customer(0, null, 0, 1_000));

        assertThat(line(q, LineCode.STORE_CREDIT)).isEqualTo(-1_000);
        assertThat(line(q, LineCode.GATEWAY_FEE)).isEqualTo(67);
        assertThat(q.total()).isEqualTo(Money.ofMinor(2_766, Currency.EUR));
    }

    @Test
    @DisplayName("no credit asked for: the quote is exactly as before")
    void unchanged() {
        Quote q = engine.quote(champs15(Currency.INR, 400_000), BigDecimal.ONE,
                new CustomerPricingContext(0L, 0L, 0L, false, null, 0, null, 0));
        assertThat(q.storeCreditMinor()).isZero();
        assertThat(q.lines().stream().map(QuoteLine::code)).doesNotContain(LineCode.STORE_CREDIT);
        assertThat(q.total().minor()).isEqualTo(410_000);
    }

    @Test
    @DisplayName("the credit is signed: the same quote claiming more, less or none fails verification")
    void signed() {
        QuoteSigner signer = new QuoteSigner("test-only-quote-secret-00000000000000000000000000000");
        Quote q = engine.quote(champs15(Currency.INR, 400_000), BigDecimal.ONE, customer(0, null, 0, 47_500));
        String signature = signer.sign(q, "acc_buyer");
        assertThat(signer.verify(q, "acc_buyer", signature, NOW)).isTrue();

        for (long claimed : List.of(0L, 47_499L, 100_000L)) {
            Quote altered = new Quote(q.quoteId(), q.season(), q.sku(), q.platform(), q.variant(), q.quantity(),
                    q.currency(), q.lines(), q.subtotal(), q.total(), q.pointsRedeemed(), q.pointsEarned(),
                    q.referralCode(), q.couponCode(), q.issuedAt(), q.expiresAt(), q.priceVersion(), claimed);
            assertThat(signer.verify(altered, "acc_buyer", signature, NOW)).as("credit %d", claimed).isFalse();
        }
    }
}
