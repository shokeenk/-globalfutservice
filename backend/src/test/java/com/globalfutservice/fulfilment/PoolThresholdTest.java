package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.util.List;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.fulfilment.FutTransferClient.BuyConditions;
import com.globalfutservice.fulfilment.FutTransferClient.PoolSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * buyNowThreshold for a public-pool order: FUT Transfer's clearing supplier price for the order's
 * platform, plus the margin, within the limit -- never 0, never the quantity, never our own price.
 */
class PoolThresholdTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** Console: 3.50 for 20K, 4.00 for 30K, 5.00 for 1000K. PC: 6.00 for 40K, 7.10 for 1000K. */
    private static final BuyConditions MARKET = new BuyConditions(List.of(
            new PoolSupplier(d("4.00"), d("30"), d("7.10"), d("1000")),
            new PoolSupplier(d("5.00"), d("1000"), d("0"), d("0")),
            new PoolSupplier(d("3.50"), d("20"), d("6.00"), d("40")),
            // No price, or no stock: never counted.
            new PoolSupplier(d("0"), d("500"), null, null),
            new PoolSupplier(d("1.00"), d("0"), d("1.00"), d("0"))),
            d("0"), d("0.25"), d("155.58"));

    private static AppProperties.FutTransferPublicPool pool(String fixed, String margin, String console, String pc) {
        return new AppProperties.FutTransferPublicPool(AppProperties.BuyNowThresholdMode.FIXED,
                fixed == null ? null : d(fixed), false, null, d(margin), console == null ? null : d(console),
                pc == null ? null : d(pc));
    }

    private static final AppProperties.FutTransferPublicPool DEFAULTS = pool(null, "10", null, null);

    private static PoolThreshold.Price price(PoolThreshold.Result r) {
        assertThat(r).isInstanceOf(PoolThreshold.Price.class);
        return (PoolThreshold.Price) r;
    }

    @Test
    @DisplayName("PlayStation and Xbox: the console price at which the cheapest stock covers the order, + 10%")
    void console() {
        // 20K at 3.50 covers 10K and 20K; 50K needs the 4.00 supplier too; 500K needs the 5.00 one.
        assertThat(price(PoolThreshold.decide(MARKET, Platform.PLAYSTATION, 20, DEFAULTS)).threshold())
                .isEqualByComparingTo("3.85");
        PoolThreshold.Price p50 = price(PoolThreshold.decide(MARKET, Platform.PLAYSTATION, 50, DEFAULTS));
        assertThat(p50.supplierRate()).isEqualByComparingTo("4.00");
        assertThat(p50.marginPct()).isEqualByComparingTo("10");
        assertThat(p50.threshold()).isEqualByComparingTo("4.40");
        assertThat(price(PoolThreshold.decide(MARKET, Platform.PLAYSTATION, 500, DEFAULTS)).threshold())
                .isEqualByComparingTo("5.50");
        // Xbox is the same market as PlayStation.
        assertThat(price(PoolThreshold.decide(MARKET, Platform.XBOX, 50, DEFAULTS)).threshold())
                .isEqualByComparingTo("4.40");
    }

    @Test
    @DisplayName("PC: the PC price fields, not the console ones")
    void pc() {
        assertThat(price(PoolThreshold.decide(MARKET, Platform.PC, 40, DEFAULTS)).threshold())
                .isEqualByComparingTo("6.60");
        PoolThreshold.Price p500 = price(PoolThreshold.decide(MARKET, Platform.PC, 500, DEFAULTS));
        assertThat(p500.supplierRate()).isEqualByComparingTo("7.10");
        assertThat(p500.threshold()).isEqualByComparingTo("7.81");
    }

    @Test
    @DisplayName("the margin is a setting, and the result is rounded to 2 decimals")
    void marginAndRounding() {
        BuyConditions odd = new BuyConditions(List.of(new PoolSupplier(d("3.33"), d("100"), null, null)), null, null, null);
        assertThat(price(PoolThreshold.decide(odd, Platform.PLAYSTATION, 50, DEFAULTS)).threshold())
                .isEqualByComparingTo("3.66");
        assertThat(price(PoolThreshold.decide(odd, Platform.PLAYSTATION, 50, pool(null, "25", null, null))).threshold())
                .isEqualByComparingTo("4.16");
        assertThat(price(PoolThreshold.decide(odd, Platform.PLAYSTATION, 50, pool(null, "0", null, null))).threshold())
                .isEqualByComparingTo("3.33");
    }

    @Test
    @DisplayName("the limit: the market above it means no send; only the margin above it means the limit is sent")
    void limits() {
        PoolThreshold.Result above = PoolThreshold.decide(MARKET, Platform.PLAYSTATION, 500, pool(null, "10", "4.80", null));
        assertThat(above).isInstanceOf(PoolThreshold.AboveLimit.class);
        assertThat(((PoolThreshold.AboveLimit) above).message())
                .isEqualTo("FUT Transfer price €5/100K is above your limit €4.8.");

        PoolThreshold.Price capped = price(PoolThreshold.decide(MARKET, Platform.PLAYSTATION, 50,
                pool(null, "10", "4.20", null)));
        assertThat(capped.threshold()).isEqualByComparingTo("4.20");
        assertThat(capped.cappedAtLimit()).isTrue();

        // Each platform has its own limit.
        assertThat(PoolThreshold.decide(MARKET, Platform.PC, 40, pool(null, "10", "4.20", "9")))
                .isInstanceOf(PoolThreshold.Price.class);
    }

    @Test
    @DisplayName("no prices read, or not enough stock priced: nothing to send, tried again later")
    void unavailable() {
        assertThat(PoolThreshold.decide(null, Platform.PLAYSTATION, 50, DEFAULTS))
                .isInstanceOf(PoolThreshold.Unavailable.class);
        assertThat(PoolThreshold.decide(new BuyConditions(List.of(), null, null, null), Platform.PLAYSTATION, 50, DEFAULTS))
                .isInstanceOf(PoolThreshold.Unavailable.class);
        PoolThreshold.Result tooBig = PoolThreshold.decide(MARKET, Platform.PC, 5000, DEFAULTS);
        assertThat(tooBig).isInstanceOf(PoolThreshold.Unavailable.class);
        assertThat(((PoolThreshold.Unavailable) tooBig).reason()).contains("5000K").contains("1040K in stock");
    }

    @Test
    @DisplayName("never 0, never negative, never the order's quantity: the same market gives the same price for any "
            + "size it covers")
    void neverZeroNeverQuantity() {
        BuyConditions deep = new BuyConditions(List.of(new PoolSupplier(d("4.25"), d("100000"), d("4.25"), d("100000"))),
                null, null, null);
        for (long k : new long[]{6, 50, 100, 500, 1000, 5000}) {
            for (Platform p : Platform.values()) {
                BigDecimal t = price(PoolThreshold.decide(deep, p, k, DEFAULTS)).threshold();
                assertThat(t).as(k + "K " + p).isEqualByComparingTo("4.68");
                assertThat(t.signum()).isPositive();
                assertThat(t).isNotEqualByComparingTo(BigDecimal.valueOf(k));
            }
        }
        // Only zero-priced stock: nothing usable, never a 0 threshold.
        BuyConditions free = new BuyConditions(List.of(new PoolSupplier(d("0"), d("1000"), d("0"), d("1000"))),
                null, null, null);
        assertThat(PoolThreshold.decide(free, Platform.PLAYSTATION, 50, DEFAULTS))
                .isInstanceOf(PoolThreshold.Unavailable.class);
        // A fixed 0 or below (startup refuses it; this is the last guard): nothing sent.
        assertThat(PoolThreshold.decide(MARKET, Platform.PLAYSTATION, 50, pool("0", "10", null, null)))
                .isInstanceOf(PoolThreshold.Unavailable.class);
        assertThat(PoolThreshold.decide(MARKET, Platform.PLAYSTATION, 50, pool("-1", "10", null, null)))
                .isInstanceOf(PoolThreshold.Unavailable.class);
    }

    @Test
    @DisplayName("an emergency fixed threshold: sent as set within the limit, refused above it")
    void fixedOverride() {
        PoolThreshold.Price fixed = price(PoolThreshold.decide(MARKET, Platform.PLAYSTATION, 50,
                pool("4.10", "10", "4.50", null)));
        assertThat(fixed.threshold()).isEqualByComparingTo("4.10");
        assertThat(fixed.fixed()).isTrue();
        assertThat(fixed.supplierRate()).isEqualByComparingTo("4.00");
        // It needs no prices to be read.
        assertThat(price(PoolThreshold.decide(null, Platform.PLAYSTATION, 50, pool("4.10", "10", null, null))).threshold())
                .isEqualByComparingTo("4.10");
        assertThat(PoolThreshold.decide(MARKET, Platform.PLAYSTATION, 50, pool("4.60", "10", "4.50", null)))
                .isInstanceOf(PoolThreshold.AboveLimit.class);
    }

    @Test
    @DisplayName("estimated cost: the order in 100Ks at the supplier rate, the transfer fee counted once per 100K")
    void estimatedCost() {
        assertThat(PoolThreshold.estimatedCost(d("4.00"), d("0"), 50)).isEqualByComparingTo("2.00");
        assertThat(PoolThreshold.estimatedCost(d("4.00"), d("0.10"), 500)).isEqualByComparingTo("20.50");
        assertThat(PoolThreshold.estimatedCost(null, d("0.10"), 500)).isNull();
    }
}
