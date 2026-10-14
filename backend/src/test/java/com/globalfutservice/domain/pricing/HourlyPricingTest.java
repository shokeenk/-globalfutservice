package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.catalog.PriceUnit;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A listing sold by the hour: the price times the whole hours the customer chose, from one
 * to the listing's cap, never assumed; and a fixed-price listing priced once whatever
 * quantity arrives.
 */
class HourlyPricingTest {

    private final PricingEngine engine = new PricingEngine(new PricingPolicy(500, MarketTaxMode.INCLUDED, 250,
            GatewayFeeMode.PASS_THROUGH, 2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true),
            Clock.systemUTC(), () -> "q_hourly");

    private static final CustomerPricingContext NOBODY = new CustomerPricingContext(0L, 0L, 0L, false, null, 0, null, 0);

    private static RateCard hourly(int cap) {
        return new RateCard("FC27", Sku.BOOST_OBJECTIVES, null, "LISTING_7", "Gallery Tokens",
                Money.ofMinor(65_000, Currency.INR), BigDecimal.ONE, BigDecimal.valueOf(cap), BigDecimal.ONE, null,
                PriceUnit.PER_HOUR);
    }

    @Test
    @DisplayName("₹650/hour × 3 hours = ₹1,950, with the 2.5% fee on top; the hours are in the line")
    void priceTimesHours() {
        Quote quote = engine.quote(hourly(50), new BigDecimal("3"), NOBODY);

        assertThat(quote.subtotal().minor()).isEqualTo(195_000);
        assertThat(quote.lines().get(0).label()).isEqualTo("Objectives — Gallery Tokens (3 hours)");
        assertThat(quote.lines().get(0).amount().minor()).isEqualTo(195_000);
        assertThat(quote.total().minor()).isEqualTo(199_875);
        assertThat(quote.quantity()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("one hour is the least, and says so in the singular")
    void oneHour() {
        Quote quote = engine.quote(hourly(50), BigDecimal.ONE, NOBODY);
        assertThat(quote.subtotal().minor()).isEqualTo(65_000);
        assertThat(quote.lines().get(0).label()).endsWith("(1 hour)");
    }

    @Test
    @DisplayName("no hours chosen: refused, never assumed")
    void hoursRequired() {
        assertThatThrownBy(() -> engine.quote(hourly(50), null, NOBODY))
                .isInstanceOf(PricingException.class).hasMessage("Choose how many hours.");
    }

    @Test
    @DisplayName("fewer than one, more than the cap, or part of an hour: refused")
    void outOfRange() {
        for (String hours : new String[] {"0", "-1", "51", "1.5"}) {
            assertThatThrownBy(() -> engine.quote(hourly(50), new BigDecimal(hours), NOBODY))
                    .as(hours).isInstanceOf(PricingException.class).hasMessageContaining("Choose");
        }
        assertThat(engine.quote(hourly(50), new BigDecimal("50"), NOBODY).subtotal().minor()).isEqualTo(3_250_000);
        // The cap is the listing's own.
        assertThatThrownBy(() -> engine.quote(hourly(5), new BigDecimal("6"), NOBODY))
                .hasMessage("Choose between 1 hour and 5 hours.");
    }

    @Test
    @DisplayName("a fixed-price listing is priced once, whatever quantity is sent")
    void fixedPrice() {
        RateCard fixed = new RateCard("FC27", Sku.BOOST_OBJECTIVES, null, "LISTING_8", "Score in 3",
                Money.ofMinor(120_000, Currency.INR), null, null, null);
        Quote quote = engine.quote(fixed, new BigDecimal("9"), NOBODY);
        assertThat(quote.subtotal().minor()).isEqualTo(120_000);
        assertThat(quote.quantity()).isEqualByComparingTo("1");
        assertThat(quote.lines().get(0).label()).isEqualTo("Objectives — Score in 3");
    }
}
