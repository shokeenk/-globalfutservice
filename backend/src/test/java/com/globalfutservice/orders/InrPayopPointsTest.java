package com.globalfutservice.orders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.pricing.CustomerPricingContext;
import com.globalfutservice.domain.pricing.GatewayFeeMode;
import com.globalfutservice.domain.pricing.MarketTaxMode;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.domain.pricing.Quote;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Loyalty points on an INR order paid through Payop: the same as by UPI. They are worked out
 * when the order is priced and frozen on it, and the order is credited with exactly those once
 * it completes; paying with a Payop method -- its own fee in place of the 2.5% -- leaves them
 * as they are.
 */
class InrPayopPointsTest {

    @Test
    @DisplayName("an INR order earns the same points paid through Payop as paid by UPI")
    void samePoints() {
        PricingEngine engine = new PricingEngine(new PricingPolicy(500, MarketTaxMode.INCLUDED, 250,
                GatewayFeeMode.PASS_THROUGH, 2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true),
                Clock.systemUTC(), () -> "q_points01");
        RateCard card = new RateCard("FC26", Sku.TRADING_SERVICE, Platform.PLAYSTATION, null, "PlayStation",
                Money.ofMinor(2_000_000, Currency.INR), new BigDecimal("0.05"), new BigDecimal("1.00"),
                new BigDecimal("0.01"));
        Quote quote = engine.quote(card, new BigDecimal("0.10"), CustomerPricingContext.guest());
        assertThat(quote.pointsEarned()).as("₹2,000 of coins, ₹2,050 with the 2.5%: one ₹2,000 step").isEqualTo(20);

        OrderEntity order = new OrderEntity("GFS-26-INRPTS01", quote.quoteId(), "FC26", Sku.TRADING_SERVICE,
                Platform.PLAYSTATION, null, new BigDecimal("0.10"), DeliveryMethod.PLAYER_AUCTION, Currency.INR,
                quote.subtotal().minor(), quote.total().minor(), "{}");
        order.setPointsEarned(quote.pointsEarned());

        // Paid through Payop: ₹2,000 plus the method's own fee, never the ₹50 of card fee.
        order.recordPayopPayment(5L, 206_250, """
                {"provider":"PAYOP","methodName":"Visa / Mastercard","feeMinor":6250,"netMinor":200000,"totalMinor":206250}
                """, null);

        assertThat(order.getTotalMinor()).isEqualTo(206_250);
        assertThat(order.getPointsEarned()).as("what the order is credited when it completes, as by UPI").isEqualTo(20);
    }
}
