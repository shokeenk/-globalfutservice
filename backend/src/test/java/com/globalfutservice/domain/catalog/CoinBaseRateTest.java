package com.globalfutservice.domain.catalog;

import com.globalfutservice.domain.money.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The factor of ten between what the owner types and what the column stores.
 *
 * <p>This conversion has no failure mode that announces itself. Off by ten in one
 * direction and every coin order costs a tenth of what it should; off by ten the other way
 * and the storefront quietly stops selling. Nothing throws, no test elsewhere goes red,
 * and the first report is a customer's. So the four prices the business actually set are
 * pinned here as literals, in both directions.
 */
class CoinBaseRateTest {

    @ParameterizedTest(name = "{0} {1} per 100K is {2} minor per million")
    @CsvSource({
            "INR, 1600.00, 1600000",
            "USD,   16.70,   16700",
            "EUR,   14.55,   14550",
            "GBP,   12.45,   12450",
    })
    @DisplayName("the owner's price becomes the rate card's, ten times over")
    void converts_to_per_million_minor(String code, String per100k, long expected) {
        Currency currency = Currency.valueOf(code);
        assertThat(CoinBaseRate.toPerMillionMinor(currency, new BigDecimal(per100k)))
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0} {2} minor per million reads back as {1} per 100K")
    @CsvSource({
            "INR, 1600, 1600000",
            "USD, 16.7,   16700",
            "EUR, 14.55,  14550",
            "GBP, 12.45,  12450",
    })
    @DisplayName("and reads back as the same number the owner typed")
    void converts_back_to_per_100k(String code, String expected, long perMillionMinor) {
        Currency currency = Currency.valueOf(code);
        assertThat(CoinBaseRate.per100k(currency, perMillionMinor))
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @ParameterizedTest(name = "{0} steps by {1} per 10,000 coins")
    @CsvSource({
            "INR, 160,   1600000",
            "USD, 1.67,    16700",
            "EUR, 1.455,   14550",
            "GBP, 1.245,   12450",
    })
    @DisplayName("a 10,000-coin step is a hundredth of the per-million price")
    void derives_the_step(String code, String expected, long perMillionMinor) {
        Currency currency = Currency.valueOf(code);
        assertThat(CoinBaseRate.per10k(currency, perMillionMinor))
                .isEqualByComparingTo(new BigDecimal(expected));
    }

    @ParameterizedTest(name = "{0} step lands on a whole minor unit: {2}")
    @CsvSource({
            "INR, 1600000, true",
            "USD,   16700, true",
            // EUR 1.455 and GBP 1.245 are half a cent. Flagged, not rejected: the price is
            // still exact end to end, and whether to accept the alternating step is the
            // owner's call, not this class's.
            "EUR,   14550, false",
            "GBP,   12450, false",
    })
    @DisplayName("half-unit steps are reported rather than silently accepted")
    void flags_fractional_steps(String code, long perMillionMinor, boolean whole) {
        assertThat(CoinBaseRate.stepIsWholeMinorUnit(perMillionMinor)).isEqualTo(whole);
    }

    @Test
    @DisplayName("a price finer than the currency can store per million is refused")
    void refuses_sub_minor_precision() {
        // EUR 14.5555 per 100K is EUR 145.555 per million — half a hundredth of a cent that
        // the column cannot hold. Storing 14555 would show the owner a price they did not
        // set the next time the screen loaded.
        assertThatThrownBy(() ->
                CoinBaseRate.toPerMillionMinor(Currency.EUR, new BigDecimal("14.5555")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("finer than this currency can store");

        // Three decimals is fine, because a tenth of a cent per 100K is a whole cent per
        // million. The rule is about what the column can hold, not about how the number
        // looks.
        assertThat(CoinBaseRate.toPerMillionMinor(Currency.EUR, new BigDecimal("14.555")))
                .isEqualTo(14_555L);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "-0.01"})
    @DisplayName("zero and negative prices are refused")
    void refuses_non_positive(String price) {
        assertThatThrownBy(() ->
                CoinBaseRate.toPerMillionMinor(Currency.INR, new BigDecimal(price)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("greater than zero");
    }

    @Test
    @DisplayName("a missing price is refused rather than read as zero")
    void refuses_null() {
        assertThatThrownBy(() -> CoinBaseRate.toPerMillionMinor(Currency.INR, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("required");
    }

    @Test
    @DisplayName("every currency survives a round trip at its own set price")
    void round_trips() {
        record Case(Currency currency, String per100k) {
        }
        for (Case c : new Case[]{
                new Case(Currency.INR, "1600.00"),
                new Case(Currency.USD, "16.70"),
                new Case(Currency.EUR, "14.55"),
                new Case(Currency.GBP, "12.45"),
        }) {
            long stored = CoinBaseRate.toPerMillionMinor(c.currency(), new BigDecimal(c.per100k()));
            assertThat(CoinBaseRate.per100k(c.currency(), stored))
                    .as("%s round trip", c.currency())
                    .isEqualByComparingTo(new BigDecimal(c.per100k()));
        }
    }
}
