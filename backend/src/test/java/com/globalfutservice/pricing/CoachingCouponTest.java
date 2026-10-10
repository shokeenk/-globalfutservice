package com.globalfutservice.pricing;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.catalog.CatalogService;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.pricing.GatewayFeeMode;
import com.globalfutservice.domain.pricing.MarketTaxMode;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.domain.pricing.QuoteSigner;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderPaymentState;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.pricing.web.QuoteDtos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A coupon on a coaching order, through the real quote path: the coupon rules, the pricing
 * engine and the signer, with only the database stood in for. Coupons carry no service
 * restriction, so coaching takes them exactly as coins and boosting do -- both products, the
 * same rules, the same messages, and the payment fee worked out after the discount.
 */
class CoachingCouponTest {

    private static final Instant NOW = Instant.parse("2026-10-09T12:00:00Z");
    private static final String SECRET = "test-only-quote-secret-00000000000000000000000000000";

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final PricingEngine engine = new PricingEngine(new PricingPolicy(500, MarketTaxMode.INCLUDED, 250,
            GatewayFeeMode.PASS_THROUGH, 2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true),
            clock, () -> "q_coach0001");
    private final CatalogService catalog = mock(CatalogService.class);
    private final CouponRepository coupons = mock(CouponRepository.class);
    private final CouponRedemptionRepository redemptions = mock(CouponRedemptionRepository.class);
    private final QuoteService quotes = new QuoteService(catalog, engine, new QuoteSigner(SECRET),
            mock(LoyaltyService.class), mock(AffiliateService.class), new CouponService(coupons, redemptions, clock),
            clock);
    private final AccountEntity buyer = mock(AccountEntity.class);

    @BeforeEach
    void setUp() {
        when(catalog.requireLiveRate(eq(Sku.COACHING), isNull(), eq("SINGLE_SESSION"), eq(Currency.EUR), any()))
                .thenReturn(card("SINGLE_SESSION", "Single session", 2_000));
        when(catalog.requireLiveRate(eq(Sku.COACHING), isNull(), eq("MONTHLY_6_SESSIONS"), eq(Currency.EUR), any()))
                .thenReturn(card("MONTHLY_6_SESSIONS", "Monthly package -- 6 sessions", 10_000));
        when(buyer.getId()).thenReturn(7L);
        when(buyer.getPublicId()).thenReturn("acc_buyer");
        when(buyer.isFirstOrder()).thenReturn(false);
        when(redemptions.countByCouponIdAndAccountId(any(), anyLong())).thenReturn(0L);
        when(coupons.findByCodeAndDeletedAtIsNull(any())).thenReturn(Optional.empty());
    }

    private static RateCard card(String variant, String label, long priceMinor) {
        return new RateCard("FC26", Sku.COACHING, null, variant, label, Money.ofMinor(priceMinor, Currency.EUR),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    }

    /** A live 10%-off code, as the admin creates it. */
    private CouponEntity coupon(String code) {
        CouponEntity c = new CouponEntity(code, 1_000, null);
        ReflectionTestUtils.setField(c, "id", 3L);
        when(coupons.findByCodeAndDeletedAtIsNull(code)).thenReturn(Optional.of(c));
        return c;
    }

    private QuoteDtos.SignedQuote quote(String variant, String couponCode) {
        return quotes.quote(new QuoteDtos.QuoteRequest("COACHING", null, variant, BigDecimal.ONE, "EUR", null,
                couponCode, 0L), buyer);
    }

    private static Map<String, Long> amounts(QuoteDtos.SignedQuote q) {
        return q.lines().stream().collect(Collectors.toMap(QuoteDtos.QuoteLineDto::code,
                QuoteDtos.QuoteLineDto::amountMinor));
    }

    @ParameterizedTest(name = "{0}: {1} - 10% coupon = {2}, then the 2.5% fee on that = {3}, total {4}")
    @CsvSource({
            "SINGLE_SESSION,     2000, 1800,  45,  1845",
            "MONTHLY_6_SESSIONS, 10000, 9000, 225, 9225",
    })
    @DisplayName("both products take the coupon; the payment fee is worked out on the price after it")
    void bothProducts(String variant, long price, long afterCoupon, long fee, long total) {
        coupon("SAVE10");
        QuoteDtos.SignedQuote q = quote(variant, "save10");

        assertThat(q.couponCode()).isEqualTo("SAVE10");
        assertThat(q.couponMessage()).isNull();
        Map<String, Long> lines = amounts(q);
        assertThat(lines).containsEntry("BASE", price).containsEntry("COUPON_DISCOUNT", -(price - afterCoupon))
                .containsEntry("GATEWAY_FEE", fee);
        assertThat(q.totalMinor()).isEqualTo(total).isEqualTo(afterCoupon + fee);
        // The fee is on the discounted price: 2.5% of what is left, not of the full price.
        assertThat(fee).isEqualTo(Math.round(afterCoupon * 0.025));
        assertThat(q.lines().stream().filter(l -> l.code().equals("COUPON_DISCOUNT")).findFirst().orElseThrow()
                .label()).isEqualTo("Coupon SAVE10 (10% off)");
    }

    @Test
    @DisplayName("what the order is frozen with: the signed quote, coupon line and all, accepted as it was priced")
    void frozenWithTheCoupon() throws Exception {
        coupon("SAVE10");
        QuoteDtos.SignedQuote q = quote("MONTHLY_6_SESSIONS", "SAVE10");

        // The order is created only from a quote that verifies; this one does, coupon included.
        assertThat(quotes.verifyOrThrow(q, buyer).couponCode()).isEqualTo("SAVE10");

        // OrderService freezes the quote as it was posted. Read back as the order page, the
        // admin's frozen breakdown and "Complete your payment" read it: the coupon is there.
        String frozen = new ObjectMapper().findAndRegisterModules().writeValueAsString(q);
        OrderEntity order = new OrderEntity("GFS-26-COACH001", q.quoteId(), "FC26", Sku.COACHING, null,
                "MONTHLY_6_SESSIONS", BigDecimal.ONE, DeliveryMethod.SCHEDULED_SESSION, Currency.EUR,
                q.subtotalMinor(), q.totalMinor(), frozen);
        OrderMapper mapper = new OrderMapper(new ObjectMapper(), mock(AppProperties.class),
                mock(DiscordVerificationService.class), mock(DiscordBotClient.class), mock(CoachingService.class),
                mock(VendorOrderLedger.class), mock(OrderPaymentState.class), clock);
        assertThat(mapper.lines(order)).extracting(OrderDtos.OrderLineDto::code)
                .containsExactly("BASE", "COUPON_DISCOUNT", "GATEWAY_FEE");
        assertThat(mapper.lines(order).get(1).amountMinor()).isEqualTo(-1_000);
        // The price a Payop method's fee is added to: after the coupon, without the 2.5%.
        assertThat(mapper.netLines(order).stream().mapToLong(OrderDtos.OrderLineDto::amountMinor).sum())
                .isEqualTo(9_000);
    }

    @Test
    @DisplayName("a code that does not exist: no discount, and the reason")
    void invalid() {
        QuoteDtos.SignedQuote q = quote("SINGLE_SESSION", "NOPE10");
        assertThat(q.couponCode()).isNull();
        assertThat(q.couponMessage()).isEqualTo("That code is not valid.");
        assertThat(amounts(q)).doesNotContainKey("COUPON_DISCOUNT");
        assertThat(q.totalMinor()).isEqualTo(2_050);
    }

    @Test
    @DisplayName("an expired code: no discount, and the reason")
    void expired() {
        coupon("OLD10").setExpiresAt(NOW.minusSeconds(60));
        QuoteDtos.SignedQuote q = quote("SINGLE_SESSION", "OLD10");
        assertThat(q.couponCode()).isNull();
        assertThat(q.couponMessage()).isEqualTo("That code has expired.");
        assertThat(q.totalMinor()).isEqualTo(2_050);
    }

    @Test
    @DisplayName("a code with every redemption taken, or one this customer has used: no discount, and the reason")
    void usedUp() {
        CouponEntity full = coupon("FULL10");
        full.setMaxRedemptions(5);
        ReflectionTestUtils.setField(full, "redeemedCount", 5);
        QuoteDtos.SignedQuote q = quote("MONTHLY_6_SESSIONS", "FULL10");
        assertThat(q.couponCode()).isNull();
        assertThat(q.couponMessage()).isEqualTo("That code has been fully claimed.");
        assertThat(q.totalMinor()).isEqualTo(10_250);

        coupon("ONCE10");
        when(redemptions.countByCouponIdAndAccountId(3L, 7L)).thenReturn(1L);
        QuoteDtos.SignedQuote again = quote("MONTHLY_6_SESSIONS", "ONCE10");
        assertThat(again.couponCode()).isNull();
        assertThat(again.couponMessage()).isEqualTo("You have already used that code.");
    }

    @Test
    @DisplayName("a minimum order applies to coaching as to everything else: the package qualifies, the single session not")
    void minimumOrder() {
        coupon("BIG10").setMinOrderMinor(5_000);
        assertThat(quote("SINGLE_SESSION", "BIG10").couponMessage())
                .isEqualTo("That code needs a larger order — the minimum is €50.00.");
        assertThat(quote("MONTHLY_6_SESSIONS", "BIG10").couponCode()).isEqualTo("BIG10");
    }
}
