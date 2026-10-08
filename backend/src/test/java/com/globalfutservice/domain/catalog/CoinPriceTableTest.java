package com.globalfutservice.domain.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import com.globalfutservice.domain.catalog.CoinPriceTable.Bracket;
import com.globalfutservice.domain.money.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * One coin price structure: which rate an amount pays, the row the engine prices from, and
 * what stops a save (errors) or is said before one (warnings).
 */
class CoinPriceTableTest {

    private static final long RS_13000 = 1_300_000; // ₹13,000 per 1M, ₹1,300 per 100K
    private static final long RS_12000 = 1_200_000;
    private static final int FUT_TRANSFER_MIN = 50;

    private static CoinPriceTable table(int minK, int maxK, int stepK, List<Integer> picks,
                                        Map<Currency, List<Bracket>> rates) {
        return new CoinPriceTable(42L, "FC26", CoinMarket.CONSOLE, minK, maxK, stepK, picks, rates);
    }

    /** Today's structure: 50K to 1M in 10K steps, the default quick picks, rupees only. */
    private static CoinPriceTable rupees(Bracket... brackets) {
        List<Bracket> all = new java.util.ArrayList<>(List.of(new Bracket(0, RS_13000)));
        all.addAll(List.of(brackets));
        return table(50, 1000, 10, CoinPriceTable.DEFAULT_QUICK_PICKS_K, Map.of(Currency.INR, all));
    }

    private static CoinPriceTable.Check check(CoinPriceTable t) {
        return t.check(List.of(Currency.INR), FUT_TRANSFER_MIN);
    }

    @Nested
    @DisplayName("pricing an amount")
    class Pricing {

        @Test
        @DisplayName("whole-order brackets: below a bracket the base rate, from it on every coin at its rate")
        void wholeOrder() {
            CoinPriceTable t = rupees(new Bracket(500, RS_12000));
            assertThat(t.perMillionMinor(Currency.INR, 490)).hasValue(RS_13000);
            assertThat(t.perMillionMinor(Currency.INR, 500)).hasValue(RS_12000);
            assertThat(t.perMillionMinor(Currency.INR, 1000)).hasValue(RS_12000);
            // 600K, all of it at ₹12,000 per 1M.
            assertThat(t.coinPrice(Currency.INR, 600).format()).isEqualTo("₹7,200.00");
            assertThat(t.perMillionMinor(Currency.USD, 500)).isEmpty();
        }

        @Test
        @DisplayName("the engine's row: the rate the amount reaches, the range in millions, the version, the platform")
        void rateCard() {
            RateCard card = rupees(new Bracket(500, RS_12000)).rateCard(Platform.XBOX, Currency.INR, new BigDecimal("0.60"));
            assertThat(card.unitPrice().minor()).isEqualTo(RS_12000);
            assertThat(card.platform()).isEqualTo(Platform.XBOX);
            assertThat(card.label()).isEqualTo("Xbox");
            assertThat(card.minQuantity()).isEqualByComparingTo("0.05");
            assertThat(card.maxQuantity()).isEqualByComparingTo("1.00");
            assertThat(card.stepQuantity()).isEqualByComparingTo("0.01");
            assertThat(card.priceVersion()).isEqualTo(42L);
            assertThat(rupees().rateCard(Platform.XBOX, Currency.USD, new BigDecimal("0.60"))).isNull();
        }

        @Test
        @DisplayName("an amount outside the slider is refused in the unit the customer chose it in")
        void messagesInK() {
            RateCard card = rupees().rateCard(Platform.PC, Currency.INR, new BigDecimal("0.04"));
            assertThatThrownBy(() -> card.validateQuantity(new BigDecimal("0.04")))
                    .hasMessage("Minimum order is 50K coins");
            assertThatThrownBy(() -> card.validateQuantity(new BigDecimal("1.01")))
                    .hasMessage("Maximum order is 1M coins — split larger orders or contact support");
            assertThatThrownBy(() -> card.validateQuantity(new BigDecimal("0.055")))
                    .hasMessage("Quantity must be in steps of 10K");
            card.validateQuantity(new BigDecimal("0.05"));
            card.validateQuantity(new BigDecimal("1.00"));
        }
    }

    @Nested
    @DisplayName("errors: what stops a save")
    class Errors {

        @Test
        @DisplayName("today's structure, at the new 50K minimum, is fine and says nothing")
        void clean() {
            CoinPriceTable.Check c = check(rupees(new Bracket(500, 1_280_000)));
            assertThat(c.errors()).isEmpty();
            assertThat(c.warnings()).isEmpty();
        }

        @Test
        @DisplayName("steps and the minimum in whole 10Ks; the maximum at most 10M, at least the minimum, on a step")
        void range() {
            assertThat(check(table(50, 1000, 5, List.of(), Map.of(Currency.INR, List.of(new Bracket(0, RS_13000))))).errors())
                    .containsExactly("The step must be a whole multiple of 10K, at least 10K.");
            assertThat(check(table(55, 1005, 10, List.of(), Map.of(Currency.INR, List.of(new Bracket(0, RS_13000))))).errors())
                    .containsExactly("The minimum must be a whole multiple of 10K, at least 10K.");
            assertThat(check(table(50, 10_010, 10, List.of(), Map.of(Currency.INR, List.of(new Bracket(0, RS_13000))))).errors())
                    .containsExactly("The maximum can be at most 10M.");
            assertThat(check(table(50, 10_000, 10, List.of(), Map.of(Currency.INR, List.of(new Bracket(0, RS_13000))))).errors())
                    .isEmpty();
            assertThat(check(table(500, 100, 10, List.of(), Map.of(Currency.INR, List.of(new Bracket(0, RS_13000))))).errors())
                    .containsExactly("The maximum must be at least the minimum.");
            assertThat(check(table(50, 1000, 20, List.of(), Map.of(Currency.INR, List.of(new Bracket(0, RS_13000))))).errors())
                    .containsExactly("The maximum must be the minimum plus whole steps of 20K: 990K or 1.01M, not 1M.");
        }

        @Test
        @DisplayName("quick picks inside the range, on a step, once each, at most eight")
        void quickPicks() {
            Map<Currency, List<Bracket>> rates = Map.of(Currency.INR, List.of(new Bracket(0, RS_13000)));
            assertThat(check(table(50, 1000, 10, List.of(50, 1000), rates)).errors()).isEmpty();
            assertThat(check(table(50, 1000, 10, List.of(40, 1500, 55, 100, 100), rates)).errors()).containsExactly(
                    "Quick pick 40K is below the minimum (50K).",
                    "Quick pick 1.5M is above the maximum (1M).",
                    "Quick pick 55K is not on a step: the slider goes 50K, 60K…",
                    "Quick pick 100K is listed twice.");
            List<Integer> nine = IntStream.rangeClosed(1, 9).map(i -> 50 + i * 10).boxed().toList();
            assertThat(check(table(50, 1000, 10, nine, rates)).errors()).containsExactly("At most 8 quick picks.");
        }

        @Test
        @DisplayName("every currency the shop sells in needs a base price")
        void everyCurrency() {
            CoinPriceTable.Check c = rupees().check(List.of(Currency.INR, Currency.USD), FUT_TRANSFER_MIN);
            assertThat(c.errors()).containsExactly("Set a base price for USD.");
        }

        @Test
        @DisplayName("brackets start above the minimum, within the range, on a step, once each")
        void brackets() {
            assertThat(check(rupees(new Bracket(50, RS_12000))).errors()).containsExactly(
                    "INR: a bracket must start above the minimum (50K); the base price covers the minimum. 50K does not.");
            assertThat(check(rupees(new Bracket(1010, RS_12000))).errors())
                    .containsExactly("INR: the bracket from 1.01M is above the maximum (1M).");
            assertThat(check(rupees(new Bracket(505, RS_12000))).errors())
                    .containsExactly("INR: the bracket from 505K is not on a step: the slider goes 500K, 510K…");
            assertThat(check(rupees(new Bracket(500, RS_12000), new Bracket(500, 1_100_000))).errors())
                    .containsExactly("INR: two brackets start at 500K.");
            Bracket[] eleven = IntStream.rangeClosed(1, 11).mapToObj(i -> new Bracket(50 + i * 10, RS_13000 - i))
                    .toArray(Bracket[]::new);
            assertThat(check(rupees(eleven)).errors()).containsExactly("At most 10 brackets for INR.");
        }
    }

    @Nested
    @DisplayName("warnings: said before a save, never stopping it")
    class Warnings {

        @Test
        @DisplayName("a bracket that makes more coins cost less: the two amounts and prices, and the run affected")
        void largerCostsLess() {
            CoinPriceTable.Check c = check(rupees(new Bracket(500, RS_12000)));
            assertThat(c.errors()).isEmpty();
            // 490K at ₹13,000 per 1M is ₹6,370; 500K at ₹12,000 is ₹6,000. From 470K (₹6,110) up it costs more.
            assertThat(c.warnings()).containsExactly("INR: 500K costs ₹6,000.00, less than 490K at ₹6,370.00. "
                    + "Every amount from 470K to 490K costs more than 500K.");
        }

        @Test
        @DisplayName("only the step just below: the two amounts and prices alone")
        void justTheStepBelow() {
            // 490K at ₹13,000 is ₹6,370; 500K at ₹12,720 is ₹6,360. 480K is ₹6,240: cheaper.
            assertThat(check(rupees(new Bracket(500, 1_272_000))).warnings())
                    .containsExactly("INR: 500K costs ₹6,360.00, less than 490K at ₹6,370.00.");
        }

        @Test
        @DisplayName("a bracket that raises the rate: per 100K as typed, and per 1M beside it")
        void rateGoesUp() {
            assertThat(check(rupees(new Bracket(500, 1_400_000))).warnings()).containsExactly(
                    "INR: from 500K the rate goes up, from ₹1,300.00 to ₹1,400.00 per 100K "
                            + "(₹13,000.00 to ₹14,000.00 per 1M).");
        }

        @Test
        @DisplayName("a minimum below FUT Transfer's 50K per transfer: allowed, with the reason it may not deliver")
        void belowTheVendorMinimum() {
            CoinPriceTable low = table(20, 1000, 10, List.of(), Map.of(Currency.INR, List.of(new Bracket(0, RS_13000))));
            CoinPriceTable.Check c = check(low);
            assertThat(c.errors()).isEmpty();
            assertThat(c.warnings()).containsExactly("Orders below 50K may not deliver properly with the current "
                    + "FUT Transfer settings: its minimum per transfer is 50K, and this minimum is 20K.");
        }
    }

    @Test
    @DisplayName("a price per 100K as typed, even when it is not a whole cent per 100K")
    void per100kAsTyped() {
        assertThat(CoinPriceTable.per100k(Currency.INR, RS_13000)).isEqualTo("₹1,300.00");
        assertThat(CoinPriceTable.per100k(Currency.EUR, 14_555)).isEqualTo(Currency.EUR.symbol() + "14.555");
        assertThat(CoinPriceTable.perMillion(Currency.INR, RS_13000)).isEqualTo("₹13,000.00");
    }

    @Test
    @DisplayName("the structures: PC alone; PlayStation and Xbox together")
    void markets() {
        assertThat(CoinMarket.of(Platform.PC)).isEqualTo(CoinMarket.PC);
        assertThat(CoinMarket.of(Platform.PLAYSTATION)).isEqualTo(CoinMarket.CONSOLE);
        assertThat(CoinMarket.of(Platform.XBOX)).isEqualTo(CoinMarket.CONSOLE);
        assertThat(CoinPriceTable.DEFAULT_QUICK_PICKS_K).containsExactly(50, 100, 250, 500, 1000);
    }
}
