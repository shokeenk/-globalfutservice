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
                           String senderGroup, String refusal) {
    }

    public static NextSend nextSend(AppProperties.FutTransfer cfg, long amountK) {
        boolean ownSenders = cfg.orderMode() == AppProperties.FutTransferOrderMode.OWN_SENDERS;
        String endpoint = ownSenders ? "/orderAPI" : "/buyCoinsAPI";
        AppProperties.FutTransferOrder order = cfg.order();
        String senderGroup = ownSenders ? order.senderGroup() : null;
        PlacementTerms.Decision decision = PlacementTerms.decide(cfg, amountK);
        if (decision instanceof PlacementTerms.Refuse refuse) {
            return new NextSend(cfg.orderMode().name(), endpoint, cfg.transferMethod(), null, null, null,
                    order.topUpEnabled(), order.autoFinishCycle(), senderGroup, refuse.reason());
        }
        VendorOrderLedger.SendTerms terms = ((PlacementTerms.Send) decision).terms();
        String source = ownSenders ? null
                : cfg.publicPool().buyNowThresholdMode() == AppProperties.BuyNowThresholdMode.ORDER_AMOUNT
                        ? "the order's amount in K" : "fixed (GFS_FUTTRANSFER_BUY_NOW_THRESHOLD)";
        return new NextSend(terms.orderMode(), endpoint, terms.transferMethod(), terms.buyNowThreshold(), source,
                terms.maxPrice(), order.topUpEnabled(), order.autoFinishCycle(), senderGroup, null);
    }

    /** True when the line should be a warning: anything but a working public-pool setup. */
    public static boolean isWarning(AppProperties.FutTransfer cfg) {
        return cfg.isConfigured() && (cfg.orderMode() != AppProperties.FutTransferOrderMode.PUBLIC_POOL
                || PlacementTerms.decide(cfg, 1) instanceof PlacementTerms.Refuse);
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
        String threshold = pool.buyNowThresholdMode() == AppProperties.BuyNowThresholdMode.ORDER_AMOUNT
                ? "the order's amount in K" : pool.buyNowThreshold().toPlainString() + " on every order";
        return LOG_PREFIX + " PUBLIC_POOL -- Approve sends /buyCoinsAPI (transferMethod=" + cfg.transferMethod()
                + ", buyNowThreshold=" + threshold + ", topUpEnabled=" + order.topUpEnabled() + ", autoFinishCycle="
                + order.autoFinishCycle() + ", maxPrice " + (pool.sendMaxPrice() ? pool.maxPrice().toPlainString()
                : "not sent") + "; no senderGroup, no supplierID).";
    }
}
