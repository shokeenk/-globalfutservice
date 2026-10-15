package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.time.Duration;

import com.globalfutservice.config.AppProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderModeReportTest {

    private static AppProperties.FutTransfer cfg(boolean enabled, String method, AppProperties.FutTransferOrderMode mode,
                                                 AppProperties.FutTransferPublicPool pool) {
        return new AppProperties.FutTransfer(enabled, "https://vendor.example.test", "api@example.test",
                VendorTestSupport.RAW_KEY, method, 1, VendorTestSupport.POLLING, Duration.ofSeconds(15), 3,
                VendorTestSupport.DOCUMENTED_CODES, VendorTestSupport.NO_BACKUP, VendorTestSupport.METHOD_3_0, true,
                Duration.ofHours(72), mode, pool, AppProperties.FutTransferAutoDispatch.OFF);
    }

    private static final AppProperties.FutTransfer POOL = cfg(true, "targetedSnipe",
            AppProperties.FutTransferOrderMode.PUBLIC_POOL, VendorTestSupport.DEFAULT_POOL);
    private static final AppProperties.FutTransfer OWN = cfg(true, "targetedSnipe",
            AppProperties.FutTransferOrderMode.OWN_SENDERS, VendorTestSupport.ORDER_AMOUNT_POOL);

    @Test
    @DisplayName("public pool at startup: the endpoint and every setting it sends, as information")
    void publicPoolLine() {
        assertThat(OrderModeReport.startupLine(POOL)).isEqualTo("FUT TRANSFER ORDER MODE: PUBLIC_POOL -- Approve "
                + "sends /buyCoinsAPI (transferMethod=targetedSnipe, buyNowThreshold (price threshold per 100K)=0 on "
                + "every order, minTransferAmount=50, topUpEnabled=300, autoFinishCycle=1, maxPrice not sent; no "
                + "senderGroup, no supplierID).");
        assertThat(OrderModeReport.isWarning(POOL)).isFalse();
    }

    @Test
    @DisplayName("the retired ORDER_AMOUNT mode still set on Render: a warning that it is ignored")
    void retiredModeWarned() {
        AppProperties.FutTransfer retired = cfg(true, "targetedSnipe", AppProperties.FutTransferOrderMode.PUBLIC_POOL,
                VendorTestSupport.ORDER_AMOUNT_POOL);
        assertThat(OrderModeReport.startupLine(retired)).contains("(price threshold per 100K)=0 on every order")
                .contains("GFS_FUTTRANSFER_BUY_NOW_THRESHOLD_MODE=ORDER_AMOUNT is set but ignored");
        assertThat(OrderModeReport.isWarning(retired)).isTrue();
    }

    @Test
    @DisplayName("own senders at startup: a warning that names /orderAPI and how to switch")
    void ownSendersLine() {
        assertThat(OrderModeReport.startupLine(OWN))
                .startsWith("FUT TRANSFER ORDER MODE: OWN_SENDERS -- Approve sends /orderAPI")
                .contains("senderGroup=-1").contains("GFS_FUTTRANSFER_ORDER_MODE=PUBLIC_POOL");
        assertThat(OrderModeReport.isWarning(OWN)).isTrue();
    }

    @Test
    @DisplayName("public pool with a method it cannot use: a warning that every Approve will be refused")
    void refusedLine() {
        AppProperties.FutTransfer wrong = cfg(true, "cycle", AppProperties.FutTransferOrderMode.PUBLIC_POOL,
                VendorTestSupport.ORDER_AMOUNT_POOL);
        assertThat(OrderModeReport.startupLine(wrong))
                .startsWith("FUT TRANSFER ORDER MODE: PUBLIC_POOL, but every Approve will be refused:")
                .contains("GFS_FUTTRANSFER_METHOD is cycle");
        assertThat(OrderModeReport.isWarning(wrong)).isTrue();
    }

    @Test
    @DisplayName("off: says coin orders are worked by hand, and is not a warning")
    void offLine() {
        AppProperties.FutTransfer off = cfg(false, "targetedSnipe", AppProperties.FutTransferOrderMode.PUBLIC_POOL,
                VendorTestSupport.ORDER_AMOUNT_POOL);
        assertThat(OrderModeReport.startupLine(off)).startsWith("FUT TRANSFER ORDER MODE: none");
        assertThat(OrderModeReport.isWarning(off)).isFalse();
    }

    @Test
    @DisplayName("the line never carries the API key")
    void noSecret() {
        for (AppProperties.FutTransfer c : new AppProperties.FutTransfer[] {POOL, OWN}) {
            assertThat(OrderModeReport.startupLine(c)).doesNotContain(VendorTestSupport.RAW_KEY);
        }
    }

    @Test
    @DisplayName("for one order: public pool sends threshold 0, never the amount, no senderGroup, and the rest as set")
    void nextSendPool() {
        OrderModeReport.NextSend next = OrderModeReport.nextSend(POOL, 500);
        assertThat(next.orderMode()).isEqualTo("PUBLIC_POOL");
        assertThat(next.endpoint()).isEqualTo("/buyCoinsAPI");
        assertThat(next.transferMethod()).isEqualTo("targetedSnipe");
        assertThat(next.buyNowThreshold()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(next.buyNowThresholdSource()).isEqualTo("the same on every order (GFS_FUTTRANSFER_BUY_NOW_THRESHOLD)");
        assertThat(next.minTransferAmount()).isEqualTo(50);
        assertThat(next.pauseIfBelowMinTransfer()).isZero();
        assertThat(next.riskLevel()).isEqualTo(1);
        assertThat(next.maxPrice()).isNull();
        assertThat(next.topUpEnabled()).isEqualTo(300);
        assertThat(next.autoFinishCycle()).isEqualTo(1);
        assertThat(next.senderGroup()).isNull();
        assertThat(next.refusal()).isNull();
    }

    @Test
    @DisplayName("for one order: own senders sends /orderAPI with a senderGroup and no threshold")
    void nextSendOwn() {
        OrderModeReport.NextSend next = OrderModeReport.nextSend(OWN, 500);
        assertThat(next.endpoint()).isEqualTo("/orderAPI");
        assertThat(next.senderGroup()).isEqualTo("-1");
        assertThat(next.buyNowThreshold()).isNull();
    }

    @Test
    @DisplayName("for one order: a refusal is reported as the reason, with nothing to send")
    void nextSendRefused() {
        AppProperties.FutTransfer maxPriceWithoutValue = cfg(true, "targetedSnipe",
                AppProperties.FutTransferOrderMode.PUBLIC_POOL,
                new AppProperties.FutTransferPublicPool(AppProperties.BuyNowThresholdMode.FIXED, null, true, null));
        OrderModeReport.NextSend next = OrderModeReport.nextSend(maxPriceWithoutValue, 500);
        assertThat(next.refusal()).contains("GFS_FUTTRANSFER_MAX_PRICE");
        assertThat(next.buyNowThreshold()).isNull();
        assertThat(next.minTransferAmount()).isEqualTo(50);
    }
}
