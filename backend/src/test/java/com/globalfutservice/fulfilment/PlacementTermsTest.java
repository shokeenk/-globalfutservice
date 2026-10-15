package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Duration;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.config.AppProperties.BuyNowThresholdMode;
import com.globalfutservice.config.AppProperties.FutTransferOrderMode;
import com.globalfutservice.config.AppProperties.FutTransferPublicPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** How configuration turns into the terms an order is sent on, or a reason it is not. */
class PlacementTermsTest {

    private static AppProperties.FutTransfer cfg(FutTransferOrderMode mode, String method, FutTransferPublicPool pool) {
        return new AppProperties.FutTransfer(true, "https://futtransfer.top", "api@example.test", "key", method, 1,
                VendorTestSupport.POLLING, Duration.ofSeconds(15), 3, VendorTestSupport.DOCUMENTED_CODES,
                "https://eatransfer.top", VendorTestSupport.METHOD_3_0, true, Duration.ofHours(72), mode, pool,
                AppProperties.FutTransferAutoDispatch.OFF);
    }

    private static FutTransferPublicPool pool(BuyNowThresholdMode mode, String threshold, boolean send, String max) {
        return new FutTransferPublicPool(mode, threshold == null ? null : new BigDecimal(threshold), send,
                max == null ? null : new BigDecimal(max));
    }

    private static VendorOrderLedger.SendTerms send(PlacementTerms.Decision d) {
        assertThat(d).isInstanceOf(PlacementTerms.Send.class);
        return ((PlacementTerms.Send) d).terms();
    }

    private static String refusal(PlacementTerms.Decision d) {
        assertThat(d).isInstanceOf(PlacementTerms.Refuse.class);
        return ((PlacementTerms.Refuse) d).reason();
    }

    @Test
    @DisplayName("the client's defaults: public pool, targetedSnipe, threshold 0 on every order, no maxPrice")
    void defaults() {
        var terms = send(PlacementTerms.decide(cfg(FutTransferOrderMode.PUBLIC_POOL, "targetedSnipe",
                VendorTestSupport.DEFAULT_POOL), 100));
        assertThat(terms).isEqualTo(new VendorOrderLedger.SendTerms("PUBLIC_POOL", "targetedSnipe",
                BigDecimal.ZERO, null));
        assertThat(terms.publicPool()).isTrue();
        // Unset or empty is 0 too.
        assertThat(send(PlacementTerms.decide(cfg(FutTransferOrderMode.PUBLIC_POOL, "targetedSnipe",
                pool(BuyNowThresholdMode.FIXED, null, false, null)), 100)).buyNowThreshold()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the order's amount is never the threshold -- whatever the amount, and even with the retired "
            + "ORDER_AMOUNT mode still set")
    void neverTheOrderAmount() {
        for (long amountK : new long[]{50, 100, 1500}) {
            for (FutTransferPublicPool p : new FutTransferPublicPool[]{VendorTestSupport.DEFAULT_POOL,
                    VendorTestSupport.ORDER_AMOUNT_POOL, pool(BuyNowThresholdMode.ORDER_AMOUNT, null, false, null)}) {
                BigDecimal sent = send(PlacementTerms.decide(cfg(FutTransferOrderMode.PUBLIC_POOL, "targetedSnipe", p),
                        amountK)).buyNowThreshold();
                assertThat(sent).as(amountK + "K, " + p).isEqualByComparingTo("0");
            }
        }
        // A configured value is sent as configured, also under the retired mode.
        assertThat(send(PlacementTerms.decide(cfg(FutTransferOrderMode.PUBLIC_POOL, "targetedSnipe",
                pool(BuyNowThresholdMode.ORDER_AMOUNT, "12", false, null)), 500)).buyNowThreshold())
                .isEqualByComparingTo("12");
    }

    @Test
    @DisplayName("a configured threshold is sent whatever the amount; maxPrice only when switched on, as configured "
            + "-- never the order's own price")
    void fixedAndMaxPrice() {
        var terms = send(PlacementTerms.decide(cfg(FutTransferOrderMode.PUBLIC_POOL, "targetedSnipe",
                pool(BuyNowThresholdMode.FIXED, "8.63", true, "9.25")), 100));
        assertThat(terms.buyNowThreshold()).isEqualByComparingTo("8.63");
        assertThat(terms.maxPrice()).isEqualByComparingTo("9.25");

        // A price set but not switched on is not sent.
        assertThat(send(PlacementTerms.decide(cfg(FutTransferOrderMode.PUBLIC_POOL, "targetedSnipe",
                pool(BuyNowThresholdMode.ORDER_AMOUNT, null, false, "9.25")), 100)).maxPrice()).isNull();
    }

    @Test
    @DisplayName("needed and missing: a reason naming the variable, not a send")
    void missingRefused() {
        assertThat(refusal(PlacementTerms.decide(cfg(FutTransferOrderMode.PUBLIC_POOL, "targetedSnipe",
                pool(BuyNowThresholdMode.ORDER_AMOUNT, null, true, null)), 100)))
                .contains("GFS_FUTTRANSFER_MAX_PRICE is not set").contains("Nothing was sent");
    }

    @Test
    @DisplayName("the public pool is only built for targetedSnipe; another method is refused, not sent")
    void poolMethod() {
        assertThat(refusal(PlacementTerms.decide(cfg(FutTransferOrderMode.PUBLIC_POOL, "snipe",
                VendorTestSupport.ORDER_AMOUNT_POOL), 100)))
                .contains("GFS_FUTTRANSFER_METHOD is snipe");
    }

    @Test
    @DisplayName("own senders: the configured method, and nothing public-pool about it, whatever those settings say")
    void ownSenders() {
        var terms = send(PlacementTerms.decide(cfg(FutTransferOrderMode.OWN_SENDERS, "targetedSnipe",
                pool(BuyNowThresholdMode.FIXED, null, true, null)), 100));
        assertThat(terms).isEqualTo(new VendorOrderLedger.SendTerms("OWN_SENDERS", "targetedSnipe", null, null));
        assertThat(terms.publicPool()).isFalse();
    }
}
