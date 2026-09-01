package com.globalfutservice.domain.pricing;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The arithmetic that decides what a customer is charged.
 *
 * <p>This is the test class to read first, and the one to be most careful about breaking.
 * Everything else in the application can be re-run; a mispriced order has already taken
 * somebody's money.
 */
class PricingEngineTest {

    private static final Instant NOW = Instant.parse("2026-08-20T10:00:00Z");

    private PricingEngine engine() {
        return engine(PricingPolicy.launchDefaults());
    }

    private PricingEngine engine(PricingPolicy policy) {
        return new PricingEngine(policy, Clock.fixed(NOW, ZoneOffset.UTC), () -> "q_test");
    }

    private RateCard coins(Platform platform, String pricePerMillion) {
        return new RateCard("FC26", Sku.TRADING_SERVICE, platform, null, null,
                Money.ofMajor(pricePerMillion, Currency.INR),
                new BigDecimal("0.5"), new BigDecimal("100"), new BigDecimal("0.5"));
    }

    /** A coaching session: EA takes no cut of one, so no market tax should ever appear. */
    private RateCard coaching() {
        return new RateCard("FC26", Sku.COACHING, null, "SINGLE_SESSION", "One hour",
                Money.ofMajor("1000.00", Currency.INR),
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    }

    private Money lineOf(Quote quote, LineCode code) {
        return quote.lines().stream()
                .filter(l -> l.code() == code)
                .map(QuoteLine::amount)
                .findFirst()
                .orElse(Money.zero(quote.currency()));
    }

    @Nested
    @DisplayName("base pricing")
    class BasePricing {

        @Test
        @DisplayName("3.5M on PC prices at list, with EA's cut already inside it")
        void worked_example() {
            Quote quote = engine().quote(coins(Platform.PC, "700.00"),
                    new BigDecimal("3.5"), CustomerPricingContext.guest());

            //  3.5M x Rs.700         = Rs.2,450.00
            //  EA market tax         = Rs.    0.00  (included in the listed price)
            //  + 2.5% processing     = Rs.   61.25  -> total Rs.2,511.25
            assertThat(lineOf(quote, LineCode.BASE)).isEqualTo(Money.ofMinor(245_000, Currency.INR));
            assertThat(lineOf(quote, LineCode.GATEWAY_FEE)).isEqualTo(Money.ofMinor(6_125, Currency.INR));
            assertThat(quote.total().minor()).isEqualTo(251_125L);
            assertThat(quote.total().format()).isEqualTo("₹2,511.25");

            // The line is still there, at zero. See MarketTax below for why.
            assertThat(lineOf(quote, LineCode.MARKET_TAX)).isEqualTo(Money.ofMinor(0, Currency.INR));
        }

        @ParameterizedTest(name = "{0} at Rs.{1}/M for {2}M totals {3} paise")
        @CsvSource({
                "PC,          700.00, 1,   71750",
                "PLAYSTATION, 600.00, 1,   61500",
                "XBOX,        600.00, 10, 615000",
                "PC,          700.00, 0.5, 35875",
        })
        void platform_rates(String platform, String rate, String quantity, long expectedMinor) {
            Quote quote = engine().quote(coins(Platform.valueOf(platform), rate),
                    new BigDecimal(quantity), CustomerPricingContext.guest());
            assertThat(quote.total().minor()).isEqualTo(expectedMinor);
        }

        @Test
        @DisplayName("a service that is not for sale cannot be quoted")
        void unsellable_sku_rejected() {
            /*
             * CARDS, not COACHING.
             *
             * This test used to quote COACHING and assert it was rejected, which was true
             * right up until coaching shipped and `Sku.COACHING` was flipped to sellable.
             * Nobody updated the test, so from that moment it was asserting the opposite
             * of the intended behaviour and failing for the right reason — the guard still
             * works; the fixture had simply become sellable. CARDS is the SKU that is
             * genuinely still not for sale.
             */
            RateCard cards = new RateCard("FC26", Sku.CARDS, null, "ICON", "Icon card",
                    Money.ofMajor("4050", Currency.INR), null, null, null);

            assertThatThrownBy(() -> engine().quote(cards, BigDecimal.ONE,
                    CustomerPricingContext.guest()))
                    .isInstanceOf(PricingException.class)
                    .hasMessageContaining("not available");
        }

        @Test
        @DisplayName("coaching is sellable now that it has shipped")
        void coaching_is_sellable() {
            // The assertion the old fixture was accidentally making. Pinned explicitly so
            // that flipping COACHING back fails here rather than somewhere unrelated.
            RateCard coaching = new RateCard("FC26", Sku.COACHING, null, "MONTHLY", "6 sessions",
                    Money.ofMajor("4050", Currency.INR), null, null, null);

            Quote quote = engine().quote(coaching, BigDecimal.ONE, CustomerPricingContext.guest());
            assertThat(quote.total().minor()).isPositive();
            assertThat(quote.linesReconcile()).isTrue();
        }

        @ParameterizedTest
        @ValueSource(strings = {"0.2", "0.3", "3.7", "101", "1000"})
        @DisplayName("quantities outside the rate card's bounds are refused")
        void quantity_bounds(String quantity) {
            assertThatThrownBy(() -> engine().quote(coins(Platform.PC, "700.00"),
                    new BigDecimal(quantity), CustomerPricingContext.guest()))
                    .isInstanceOf(PricingException.class);
        }
    }

    @Nested
    @DisplayName("discounts and the points wallet")
    class Discounts {

        @Test
        @DisplayName("a creator code discounts a first order only")
        void referral_first_order_only() {
            RateCard pc = coins(Platform.PC, "700.00");
            BigDecimal qty = new BigDecimal("3.5");

            Quote first = engine().quote(pc, qty,
                    new CustomerPricingContext(0, 0, 0, true, "VINU", 1_300, null, 0));
            Quote repeat = engine().quote(pc, qty,
                    new CustomerPricingContext(0, 0, 0, false, "VINU", 1_300, null, 0));

            assertThat(lineOf(first, LineCode.REFERRAL_DISCOUNT).minor()).isEqualTo(-31_850L);
            assertThat(first.referralCode()).isEqualTo("VINU");

            assertThat(repeat.total().minor()).isEqualTo(251_125L);
            assertThat(repeat.referralCode())
                    .as("an unused code must not be recorded on the order, or it would "
                            + "accrue commission for a sale it did not cause")
                    .isNull();
        }

        @Test
        @DisplayName("redemption is capped at 20% of the order, whatever the balance")
        void wallet_capped() {
            Quote quote = engine().quote(coins(Platform.PC, "700.00"), new BigDecimal("3.5"),
                    new CustomerPricingContext(1_000_000, 1_000_000, 0, false, null, 0, null, 0));

            // Subtotal Rs.2,450.00 -> 20% is Rs.490.00 -> 490 whole points.
            assertThat(quote.pointsRedeemed()).isEqualTo(490L);
            assertThat(lineOf(quote, LineCode.WALLET_REDEMPTION).minor()).isEqualTo(-49_000L);
        }

        @Test
        @DisplayName("a customer can never spend points they do not have")
        void wallet_clamped_to_balance() {
            Quote quote = engine().quote(coins(Platform.PC, "700.00"), new BigDecimal("3.5"),
                    new CustomerPricingContext(50, 10_000, 0, false, null, 0, null, 0));
            assertThat(quote.pointsRedeemed()).isEqualTo(50L);
        }

        @Test
        @DisplayName("processing is charged on the net, after discounts")
        void gateway_fee_follows_discounts() {
            Quote quote = engine().quote(coins(Platform.PC, "700.00"), new BigDecimal("3.5"),
                    new CustomerPricingContext(1_000, 1_000, 0, false, null, 0, null, 0));

            long net = 245_000L - 49_000L;
            assertThat(lineOf(quote, LineCode.GATEWAY_FEE).minor())
                    .isEqualTo(Math.round(net * 0.025));
        }

        @Test
        @DisplayName("no combination of discounts can produce a negative total")
        void never_negative() {
            PricingPolicy generous = new PricingPolicy(500, MarketTaxMode.INCLUDED, 250, GatewayFeeMode.PASS_THROUGH,
                    10_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true);

            Quote quote = engine(generous).quote(coins(Platform.PC, "700.00"), new BigDecimal("0.5"),
                    new CustomerPricingContext(1_000_000, 1_000_000, 0, true, "VINU", 5_000, null, 0));

            assertThat(quote.total().minor()).isGreaterThanOrEqualTo(0L);
        }
    }

    @Nested
    @DisplayName("rounding")
    class Rounding {

        @Test
        @DisplayName("the printed breakdown always adds up to the amount charged")
        void lines_always_reconcile() {
            PricingEngine engine = engine();
            int checked = 0;

            for (String rate : new String[]{"700.00", "600.00", "699.99", "633.33", "1.01"}) {
                RateCard card = coins(Platform.PC, rate);
                for (BigDecimal qty = new BigDecimal("0.5");
                     qty.compareTo(new BigDecimal("100")) <= 0;
                     qty = qty.add(new BigDecimal("0.5"))) {
                    // Doubles as the lifetime total, so the sweep walks every rung of the
                    // tier ladder — the residual-folding contract has to hold with a
                    // TIER_DISCOUNT line present, not merely without one.
                    for (long points : new long[]{0, 1, 500, 2_000, 5_000, 8_000, 10_000, 100_000}) {
                        Quote quote = engine.quote(card, qty, new CustomerPricingContext(
                                points, points, points, points % 2 == 0, "VINU", 1_300,
                                points % 3 == 0 ? "SAVE20" : null,
                                points % 3 == 0 ? 2_000 : 0));

                        assertThat(quote.linesReconcile())
                                .as("breakdown must sum to total for %s x %s with %d points",
                                        rate, qty, points)
                                .isTrue();
                        checked++;
                    }
                }
            }
            assertThat(checked).isGreaterThan(1_000);
        }
    }

    @Nested
    @DisplayName("who pays EA's cut")
    class MarketTax {

        private PricingPolicy withTax(MarketTaxMode mode) {
            return new PricingPolicy(500, mode, 250, GatewayFeeMode.PASS_THROUGH,
                    2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true);
        }

        @Test
        @DisplayName("INCLUDED charges nothing on top of the listed price")
        void included_costs_the_customer_nothing() {
            Quote quote = engine(withTax(MarketTaxMode.INCLUDED))
                    .quote(coins(Platform.PC, "700.00"), BigDecimal.ONE,
                           CustomerPricingContext.guest());

            // Rs.700 listed, + 2.5% processing, and nothing else.
            assertThat(lineOf(quote, LineCode.MARKET_TAX).minor()).isZero();
            assertThat(quote.total().minor()).isEqualTo(71_750L);
        }

        @Test
        @DisplayName("INCLUDED still shows the line, because that is the whole claim")
        void included_still_shows_the_line() {
            /*
             * Every other line in this engine is suppressed at zero. This one is not, and
             * that is deliberate: "we cover the EA tax" is a statement about a charge, and
             * it lands far harder in the row where the charge would have been than as a
             * sentence somewhere else on the page. Deleting the zero line as tidy-up would
             * quietly remove the strongest thing the pricing does for the customer.
             */
            Quote quote = engine(withTax(MarketTaxMode.INCLUDED))
                    .quote(coins(Platform.PC, "700.00"), BigDecimal.ONE,
                           CustomerPricingContext.guest());

            assertThat(quote.lines())
                    .as("the zero-value market tax line must survive")
                    .anyMatch(l -> l.code() == LineCode.MARKET_TAX);
        }

        @Test
        @DisplayName("ADDED bills it on top, as it used to")
        void added_bills_the_customer() {
            Quote quote = engine(withTax(MarketTaxMode.ADDED))
                    .quote(coins(Platform.PC, "700.00"), BigDecimal.ONE,
                           CustomerPricingContext.guest());

            // Rs.700 + 5% = Rs.735, + 2.5% processing = Rs.753.38.
            assertThat(lineOf(quote, LineCode.MARKET_TAX).minor()).isEqualTo(3_500L);
            assertThat(quote.total().minor()).isEqualTo(75_338L);
        }

        @Test
        @DisplayName("the two modes differ by exactly EA's cut, plus processing on it")
        void the_difference_is_the_tax() {
            long included = engine(withTax(MarketTaxMode.INCLUDED))
                    .quote(coins(Platform.PC, "700.00"), BigDecimal.ONE,
                           CustomerPricingContext.guest()).total().minor();
            long added = engine(withTax(MarketTaxMode.ADDED))
                    .quote(coins(Platform.PC, "700.00"), BigDecimal.ONE,
                           CustomerPricingContext.guest()).total().minor();

            // Rs.35.00 of tax, and the 2.5% the processor takes on it.
            assertThat(added - included).isEqualTo(3_588L);
        }

        @Test
        @DisplayName("coaching is untouched: EA takes no cut of a coaching session")
        void coaching_has_no_market_tax() {
            Quote quote = engine(withTax(MarketTaxMode.ADDED))
                    .quote(coaching(), BigDecimal.ONE, CustomerPricingContext.guest());

            assertThat(quote.lines()).noneMatch(l -> l.code() == LineCode.MARKET_TAX);
        }
    }

    @Nested
    @DisplayName("processing fee modes")
    class FeeModes {

        @Test
        @DisplayName("absorbed shows no fee line at all")
        void absorbed() {
            PricingPolicy policy = new PricingPolicy(500, MarketTaxMode.ADDED, 250, GatewayFeeMode.ABSORBED,
                    2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true);

            Quote quote = engine(policy).quote(coins(Platform.PC, "700.00"),
                    BigDecimal.ONE, CustomerPricingContext.guest());

            assertThat(quote.lines()).noneMatch(l -> l.code() == LineCode.GATEWAY_FEE);
            assertThat(quote.total().minor()).isEqualTo(73_500L);
        }

        @Test
        @DisplayName("gross-up leaves the merchant whole after the processor's cut")
        void gross_up() {
            PricingPolicy policy = new PricingPolicy(500, MarketTaxMode.ADDED, 250, GatewayFeeMode.GROSS_UP,
                    2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true);

            Quote quote = engine(policy).quote(coins(Platform.PC, "700.00"),
                    BigDecimal.ONE, CustomerPricingContext.guest());

            // 73,500 / 0.975 = 75,384.62 -> charged 75,385, of which 2.5% is the fee.
            assertThat(quote.total().minor()).isEqualTo(75_385L);
            assertThat(Math.round(quote.total().minor() * 0.975)).isGreaterThanOrEqualTo(73_500L);
        }
    }

    @Nested
    @DisplayName("points earning")
    class Earning {

        @ParameterizedTest(name = "Rs.{0} earns {1} points")
        @CsvSource({"1999, 0", "2000, 20", "3999, 20", "4000, 40", "10000, 100"})
        void earn_rate(String rupees, long expected) {
            assertThat(engine().pointsEarnedOn(Money.ofMajor(rupees, Currency.INR)))
                    .isEqualTo(expected);
        }

        @Test
        @DisplayName("earning is not pro-rated — it is a whole-unit rebate, as specified")
        void not_prorated() {
            long atFloor = engine().pointsEarnedOn(Money.ofMajor("2000", Currency.INR));
            long justUnderNext = engine().pointsEarnedOn(Money.ofMajor("3999", Currency.INR));
            assertThat(atFloor).isEqualTo(justUnderNext);
        }
    }
}
