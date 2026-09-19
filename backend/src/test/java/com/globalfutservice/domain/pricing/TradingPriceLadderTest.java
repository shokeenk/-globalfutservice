package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Every rung of the coin slider, priced.
 *
 * <p>{@link PricingEngineTest} proves the engine's arithmetic. This proves the specific
 * ladder the storefront sells: Rs.16,000 per million over 10,000 to 1,000,000 coins in
 * 10,000-coin steps, which is the rate card V24 installs.
 *
 * <p><b>Why walk all hundred positions rather than sample four.</b> The failure this is
 * written against is drift — a price that is right at the ends and a paisa out somewhere
 * in the middle, or a gap between two adjacent steps that is Rs.159.99 rather than
 * Rs.160. Four sampled points cannot see either. A hundred BigDecimal multiplications
 * cost nothing, and the assertion that matters is the one on the gap: it is what a
 * customer dragging the slider actually experiences.
 */
class TradingPriceLadderTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");

    /** Paise per million, as V24 writes it. */
    private static final long PER_MILLION_MINOR = 1_600_000L;

    /** What one 10,000-coin step is worth: Rs.160.00. */
    private static final long PER_STEP_MINOR = 16_000L;

    private static final BigDecimal MIN = new BigDecimal("0.01");
    private static final BigDecimal MAX = new BigDecimal("1.00");
    private static final BigDecimal STEP = new BigDecimal("0.01");

    private PricingEngine engine() {
        return new PricingEngine(PricingPolicy.launchDefaults(),
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "q_test");
    }

    /** The row V24 installs, for whichever platform — all three are priced the same. */
    private RateCard card() {
        return new RateCard("FC26", Sku.TRADING_SERVICE, Platform.PC, null, null,
                Money.ofMinor(PER_MILLION_MINOR, Currency.INR), MIN, MAX, STEP);
    }

    private long baseMinor(BigDecimal millions) {
        return engine().quote(card(), millions, CustomerPricingContext.guest())
                .lines().stream()
                .filter(l -> l.code() == LineCode.BASE)
                .map(QuoteLine::amount)
                .findFirst()
                .orElseThrow()
                .minor();
    }

    @Test
    @DisplayName("every 10K step costs exactly Rs.160 more than the one below it")
    void ladder_has_no_drift() {
        long previous = 0;
        for (int step = 1; step <= 100; step++) {
            BigDecimal millions = STEP.multiply(BigDecimal.valueOf(step));
            long base = baseMinor(millions);

            assertThat(base)
                    .as("%s million coins", millions.toPlainString())
                    .isEqualTo(PER_STEP_MINOR * step);

            if (step > 1) {
                assertThat(base - previous)
                        .as("gap between step %d and %d", step - 1, step)
                        .isEqualTo(PER_STEP_MINOR);
            }
            previous = base;
        }
    }

    @ParameterizedTest(name = "{0} coins costs {1}")
    @CsvSource({
            "10000,   0.01, ₹160.00",
            "100000,  0.10, ₹1600.00",
            "500000,  0.50, ₹8000.00",
            "1000000, 1.00, ₹16000.00",
    })
    @DisplayName("the worked examples the price was specified with")
    void worked_examples(long coins, String millions, String ignoredFormat) {
        // Rs.1,600 per 100K, stated as paise so the assertion does not depend on how the
        // formatter groups digits or which rupee glyph the locale picks.
        long expected = coins / 10_000 * PER_STEP_MINOR;
        assertThat(baseMinor(new BigDecimal(millions))).isEqualTo(expected);
    }

    @Test
    @DisplayName("the base line is exact, so nothing is folded back into it")
    void base_carries_no_rounding_residue() {
        /*
         * The engine reconciles a rounded breakdown against a rounded total by pushing
         * the difference into BASE. On this ladder there is never a difference: the base
         * is a whole number of paise at every step, and the 2.5% fee on a multiple of
         * Rs.160 is a whole number too. If this fails, the rate card has acquired a price
         * that does not divide cleanly and the breakdown on screen has started absorbing
         * stray paise.
         */
        for (int step = 1; step <= 100; step++) {
            BigDecimal millions = STEP.multiply(BigDecimal.valueOf(step));
            Quote quote = engine().quote(card(), millions, CustomerPricingContext.guest());
            assertThat(quote.linesReconcile())
                    .as("%s million coins", millions.toPlainString())
                    .isTrue();
            assertThat(quote.total().minor() % 25L)
                    .as("Rs.160 x %d plus 2.5%% is a whole number of paise", step)
                    .isZero();
        }
    }

    @ParameterizedTest(name = "{0} million reads as {1}")
    @CsvSource({
            "0.01, 10K",
            "0.10, 100K",
            "0.25, 250K",
            "0.99, 990K",
            "1.00, 1M",
    })
    @DisplayName("the receipt line says what the customer bought, not what the column stores")
    void base_line_is_labelled_in_coins(String millions, String expected) {
        String label = engine().quote(card(), new BigDecimal(millions),
                        CustomerPricingContext.guest())
                .lines().stream()
                .filter(l -> l.code() == LineCode.BASE)
                .map(QuoteLine::label)
                .findFirst()
                .orElseThrow();

        // "Buy Coins — 100K (PC)", never "Buy Coins — 0.1M (PC)".
        assertThat(label).isEqualTo("Buy Coins — " + expected + " (PC)");
    }

    @Test
    @DisplayName("below 10,000 coins and above 1,000,000 are both refused")
    void bounds_are_enforced() {
        assertThatThrownBy(() -> engine().quote(card(), new BigDecimal("0.009"),
                CustomerPricingContext.guest()))
                .isInstanceOf(PricingException.class)
                .hasMessageContaining("Minimum");

        assertThatThrownBy(() -> engine().quote(card(), new BigDecimal("1.01"),
                CustomerPricingContext.guest()))
                .isInstanceOf(PricingException.class)
                .hasMessageContaining("Maximum");
    }

    @Test
    @DisplayName("an amount between two steps is refused rather than silently rounded")
    void off_step_is_refused() {
        // 15,000 coins. The storefront snaps before it asks; this is the server refusing
        // to price what it would have to invent a rule to price.
        assertThatThrownBy(() -> engine().quote(card(), new BigDecimal("0.015"),
                CustomerPricingContext.guest()))
                .isInstanceOf(PricingException.class)
                .hasMessageContaining("steps of");
    }
}
