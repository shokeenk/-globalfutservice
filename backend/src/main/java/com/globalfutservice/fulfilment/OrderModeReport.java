package com.globalfutservice.fulfilment;

import java.math.BigDecimal;

import com.globalfutservice.config.AppProperties;

/**
 * What Approve sends a coin order as, said the same way in the startup log and on the admin
 * order page.
 *
 * <p>Both are built on {@link PlacementTerms#decide}, the decision Approve itself takes, so
 * neither can describe a send that Approve would not make. Settings only -- never a key or a
 * customer's sign-in.
 */
public final class OrderModeReport {

    private OrderModeReport() {
    }

    /** The prefix every startup line carries, so one log search finds it. */
    public static final String LOG_PREFIX = "FUT TRANSFER ORDER MODE:";

    /**
     * How Approve would send an order of {@code amountK} right now.
     *
     * @param buyNowThreshold       what a public-pool order would send; null for own senders
     * @param buyNowThresholdSource where that number comes from, in words
     * @param senderGroup           sent only by own-senders orders; null otherwise
     * @param refusal               why Approve would send nothing at all, or null
     */
    public record NextSend(String orderMode, String endpoint, String transferMethod, BigDecimal buyNowThreshold,
                           String buyNowThresholdSource, BigDecimal maxPrice, int topUpEnabled, int autoFinishCycle,
                           String senderGroup, String refusal, int minTransferAmount, int pauseIfBelowMinTransfer,
                           int riskLevel) {
    }

    /** What the admin page and the startup line call {@code buyNowThreshold}. */
    public static final String THRESHOLD_LABEL = "price threshold per 100K";

    public static NextSend nextSend(AppProperties.FutTransfer cfg, long amountK) {
        boolean ownSenders = cfg.orderMode() == AppProperties.FutTransferOrderMode.OWN_SENDERS;
        String endpoint = ownSenders ? "/orderAPI" : "/buyCoinsAPI";
        AppProperties.FutTransferOrder order = cfg.order();
        String senderGroup = ownSenders ? order.senderGroup() : null;
        PlacementTerms.Decision decision = PlacementTerms.decide(cfg, amountK);
        if (decision instanceof PlacementTerms.Refuse refuse) {
            return new NextSend(cfg.orderMode().name(), endpoint, cfg.transferMethod(), null, null, null,
                    order.topUpEnabled(), order.autoFinishCycle(), senderGroup, refuse.reason(),
                    order.minTransferAmount(), order.pauseIfBelowMinTransfer(), cfg.riskLevel());
        }
        VendorOrderLedger.SendTerms terms = ((PlacementTerms.Send) decision).terms();
        String source = ownSenders ? null : "the same on every order (GFS_FUTTRANSFER_BUY_NOW_THRESHOLD)";
        return new NextSend(terms.orderMode(), endpoint, terms.transferMethod(), terms.buyNowThreshold(), source,
                terms.maxPrice(), order.topUpEnabled(), order.autoFinishCycle(), senderGroup, null,
                order.minTransferAmount(), order.pauseIfBelowMinTransfer(), cfg.riskLevel());
    }

    /** A retired setting still saying the order's amount: ignored, and worth a warning. */
    private static boolean retiredModeSet(AppProperties.FutTransfer cfg) {
        return cfg.publicPool().buyNowThresholdMode() == AppProperties.BuyNowThresholdMode.ORDER_AMOUNT;
    }

    /** True when the line should be a warning: anything but a working public-pool setup. */
    public static boolean isWarning(AppProperties.FutTransfer cfg) {
        return cfg.isConfigured() && (cfg.orderMode() != AppProperties.FutTransferOrderMode.PUBLIC_POOL
                || PlacementTerms.decide(cfg, 1) instanceof PlacementTerms.Refuse || retiredModeSet(cfg));
    }

    /** The line logged at every startup. */
    public static String startupLine(AppProperties.FutTransfer cfg) {
        if (!cfg.isConfigured()) {
            return LOG_PREFIX + " none -- FUT Transfer is off or has no API user/key, so coin orders stop at "
                    + "Ready for delivery and are worked by hand.";
        }
        AppProperties.FutTransferOrder order = cfg.order();
        if (cfg.orderMode() == AppProperties.FutTransferOrderMode.OWN_SENDERS) {
            return LOG_PREFIX + " OWN_SENDERS -- Approve sends /orderAPI from our own sender accounts (transferMethod="
                    + cfg.transferMethod() + ", senderGroup=" + order.senderGroup() + ", topUpEnabled="
                    + order.topUpEnabled() + ", autoFinishCycle=" + order.autoFinishCycle()
                    + "). Set GFS_FUTTRANSFER_ORDER_MODE=PUBLIC_POOL to buy from the public pool instead.";
        }
        if (PlacementTerms.decide(cfg, 1) instanceof PlacementTerms.Refuse refuse) {
            return LOG_PREFIX + " PUBLIC_POOL, but every Approve will be refused: " + refuse.reason();
        }
        AppProperties.FutTransferPublicPool pool = cfg.publicPool();
        return LOG_PREFIX + " PUBLIC_POOL -- Approve sends /buyCoinsAPI (transferMethod=" + cfg.transferMethod()
                + ", buyNowThreshold (" + THRESHOLD_LABEL + ")=" + pool.threshold().toPlainString() + " on every order"
                + ", minTransferAmount=" + order.minTransferAmount() + ", topUpEnabled=" + order.topUpEnabled()
                + ", autoFinishCycle=" + order.autoFinishCycle() + ", maxPrice "
                + (pool.sendMaxPrice() ? pool.maxPrice().toPlainString() : "not sent") + "; no senderGroup, no supplierID)."
                + (retiredModeSet(cfg) ? " GFS_FUTTRANSFER_BUY_NOW_THRESHOLD_MODE=ORDER_AMOUNT is set but ignored: the "
                + "threshold never comes from the order's amount. Remove the variable." : "");
    }
}
