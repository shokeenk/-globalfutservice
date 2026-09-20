package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The same coin ladder, in all four currencies V25 prices.
 *
 * <p>{@link TradingPriceLadderTest} walks the rupee ladder, where every step lands on a
 * whole paisa and the assertion can simply be "each step costs exactly Rs.160 more". Three
 * of the four currencies do not have that luxury, and this is the test for what is
 * actually true across all of them.
 *
 * <p><b>The distinction that matters: rounding is not drift.</b> At EUR 145.50 and
 * GBP 124.50 per million, one 10,000-coin step is worth half a minor unit —
 * EUR 1.455, GBP 1.245. Half a cent cannot be charged, so something has to round. The
 * property worth proving is <i>where</i>:
 *
 * <ul>
 *   <li>the engine multiplies the full quantity once, in exact decimal, and rounds a
 *       single time at the total — so the price of 730,000 coins is computed from
 *       730,000 coins, never by adding up 73 rounded steps;</li>
 *   <li>therefore the charge is always within half a minor unit of the exact figure, at
 *       every one of the hundred positions — it does not creep as the slider moves
 *       right, which is what drift would look like and what sampling four points would
 *       miss.</li>
 * </ul>
 *
 * <p>The expected total is recomputed here from the base rate rather than read back from
 * the engine, so this is an independent check of the arithmetic and not a restatement of
 * it.
 */
class TradingCurrencyLadderTest {

    private static final Instant NOW = Instant.parse("2026-09-19T10:00:00Z");
    private static final MathContext EXACT = new MathContext(40, RoundingMode.HALF_UP);

    private static final BigDecimal MIN = new BigDecimal("0.01");
    private static final BigDecimal MAX = new BigDecimal("1.00");
    private static final BigDecimal STEP = new BigDecimal("0.01");

    /** 2.5% processing, passed through. EA's 5% is INCLUDED, so it adds nothing. */
    private static final BigDecimal GATEWAY_MULTIPLIER = new BigDecimal("1.025");

    private PricingEngine engine() {
        return new PricingEngine(PricingPolicy.launchDefaults(),
                Clock.fixed(NOW, ZoneOffset.UTC), () -> "q_test");
    }

    private RateCard card(Currency currency, long perMillionMinor) {
        return new RateCard("FC26", Sku.TRADING_SERVICE, Platform.PC, null, null,
                Money.ofMinor(perMillionMinor, currency), MIN, MAX, STEP);
    }

    private Quote quote(Currency currency, long perMillionMinor, BigDecimal millions) {
        return engine().quote(card(currency, perMillionMinor), millions,
                CustomerPricingContext.guest());
    }

    /** The rate card V25 installs: the price of 100,000 coins, times ten. */
    @ParameterizedTest(name = "{0} at {1} minor per million")
    @CsvSource({
            "INR, 1600000",
            "USD,   16700",
            "EUR,   14550",
            "GBP,   12450",
    })
    @DisplayName("every step is the exact price rounded once, never a running total")
    void ladder_never_drifts(String code, long perMillionMinor) {
        Currency currency = Currency.valueOf(code);

        for (int step = 1; step <= 100; step++) {
            BigDecimal millions = STEP.multiply(BigDecimal.valueOf(step));

            // What the customer should be charged, computed from the rate independently.
            BigDecimal exactBase = BigDecimal.valueOf(perMillionMinor).multiply(millions, EXACT);
            BigDecimal exactTotal = exactBase.multiply(GATEWAY_MULTIPLIER, EXACT);
            long expected = exactTotal.setScale(0, RoundingMode.HALF_UP).longValueExact();

            long actual = quote(currency, perMillionMinor, millions).total().minor();

            assertThat(actual)
                    .as("%s total at %s million coins", code, millions.toPlainString())
                    .isEqualTo(expected);

            // Restated as the property that matters: the gap between what is charged and
            // the exact figure never exceeds half a minor unit, at any position on the
            // slider. Drift would show up here as a gap that grows with `step`.
            BigDecimal error = BigDecimal.valueOf(actual).subtract(exactTotal, EXACT).abs();
            assertThat(error.compareTo(new BigDecimal("0.5")))
                    .as("%s is within half a minor unit at step %d", code, step)
                    .isLessThanOrEqualTo(0);
        }
    }

    @ParameterizedTest(name = "{0}: 100,000 coins costs {1} minor units before fees")
    @CsvSource({
            // The brief's four numbers, as base (pre-fee) minor units at 100K coins.
            "INR, 1600000, 160000",
            "USD,   16700,   1670",
            "EUR,   14550,   1455",
            "GBP,   12450,   1245",
    })
    @DisplayName("100,000 coins costs what the owner set, in every currency")
    void hundred_thousand_is_the_set_price(String code, long perMillionMinor, long expectedBase) {
        Currency currency = Currency.valueOf(code);
        long base = quote(currency, perMillionMinor, new BigDecimal("0.10"))
                .lines().stream()
                .filter(l -> l.code() == LineCode.BASE)
                .map(QuoteLine::amount)
                .findFirst()
                .orElseThrow()
                .minor();

        // Rs.1,600.00 / $16.70 / EUR 14.55 / GBP 12.45 — a whole minor unit in all four at
        // 100K, even though EUR and GBP are half a unit at the 10K step below it.
        assertThat(base).as("%s base at 100K coins", code).isEqualTo(expectedBase);
    }

    @ParameterizedTest(name = "{0} steps by {1} minor units, exactly")
    @CsvSource({
            "INR, 16000",
            "USD,   167",
    })
    @DisplayName("where a step is a whole minor unit, the gap is identical at all 99 joins")
    void whole_unit_currencies_step_evenly(String code, long perStepMinor) {
        Currency currency = Currency.valueOf(code);
        long perMillionMinor = perStepMinor * 100;

        long previous = 0;
        for (int step = 1; step <= 100; step++) {
            BigDecimal millions = STEP.multiply(BigDecimal.valueOf(step));
            long base = quote(currency, perMillionMinor, millions).lines().stream()
                    .filter(l -> l.code() == LineCode.BASE)
                    .map(QuoteLine::amount)
                    .findFirst()
                    .orElseThrow()
                    .minor();

            assertThat(base).as("%s base at step %d", code, step)
                    .isEqualTo(perStepMinor * step);
            if (step > 1) {
                assertThat(base - previous).as("%s gap at step %d", code, step)
                        .isEqualTo(perStepMinor);
            }
            previous = base;
        }
    }

    @ParameterizedTest(name = "{0} steps alternate around {1}.5 minor units")
    @CsvSource({
            "EUR, 14550, 145",
            "GBP, 12450, 124",
    })
    @DisplayName("where a step is half a minor unit, the gap alternates by one and no more")
    void half_unit_currencies_alternate_without_accumulating(String code, long perMillionMinor,
                                                             long floorStep) {
        Currency currency = Currency.valueOf(code);

        long previous = 0;
        for (int step = 1; step <= 100; step++) {
            BigDecimal millions = STEP.multiply(BigDecimal.valueOf(step));
            long base = quote(currency, perMillionMinor, millions).lines().stream()
                    .filter(l -> l.code() == LineCode.BASE)
                    .map(QuoteLine::amount)
                    .findFirst()
                    .orElseThrow()
                    .minor();

            if (step > 1) {
                long gap = base - previous;
                // Never anything but one of the two neighbours of the exact half-unit
                // step. A gap of floorStep-1 or floorStep+2 anywhere would mean error had
                // accumulated across positions instead of being resolved at each one.
                assertThat(gap).as("%s gap at step %d", code, step)
                        .isIn(floorStep, floorStep + 1);
            }
            previous = base;
        }

        // And at the top of the slider the accumulated result is exactly the round number
        // the rate says it should be: 100 steps of half a unit is a whole one.
        long atOneMillion = quote(currency, perMillionMinor, MAX).lines().stream()
                .filter(l -> l.code() == LineCode.BASE)
                .map(QuoteLine::amount)
                .findFirst()
                .orElseThrow()
                .minor();
        assertThat(atOneMillion).as("%s base at 1M coins", code).isEqualTo(perMillionMinor);
    }
}
