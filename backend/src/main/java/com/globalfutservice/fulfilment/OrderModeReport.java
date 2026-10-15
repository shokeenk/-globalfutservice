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
     * @param supplierRate          FUT Transfer's clearing supplier price now, EUR per 100K; null if not read
     * @param marginPct             the margin added, in percent; null for a fixed threshold
     * @param limit                 the configured ceiling for the platform, EUR per 100K; null for none
     * @param estimatedCostEur      the order at the supplier rate, fees once, EUR; null without a rate
     * @param priceNote             how the threshold came about, in words
     */
    public record NextSend(String orderMode, String endpoint, String transferMethod, BigDecimal buyNowThreshold,
                           String buyNowThresholdSource, BigDecimal maxPrice, int topUpEnabled, int autoFinishCycle,
                           String senderGroup, String refusal, int minTransferAmount, int pauseIfBelowMinTransfer,
                           int riskLevel, BigDecimal supplierRate, BigDecimal marginPct, BigDecimal limit,
                           BigDecimal estimatedCostEur, String priceNote) {

        public NextSend(String orderMode, String endpoint, String transferMethod, BigDecimal buyNowThreshold,
                        String buyNowThresholdSource, BigDecimal maxPrice, int topUpEnabled, int autoFinishCycle,
                        String senderGroup, String refusal, int minTransferAmount, int pauseIfBelowMinTransfer,
                        int riskLevel) {
            this(orderMode, endpoint, transferMethod, buyNowThreshold, buyNowThresholdSource, maxPrice, topUpEnabled,
                    autoFinishCycle, senderGroup, refusal, minTransferAmount, pauseIfBelowMinTransfer, riskLevel,
                    null, null, null, null, null);
        }
    }

    /** What the admin page and the startup line call {@code buyNowThreshold}. */
    public static final String THRESHOLD_LABEL = "price ceiling, EUR per 100K";

    /**
     * As {@link #nextSend(AppProperties.FutTransfer, long)}, with FUT Transfer's prices as read just
     * now for the order's platform (null when the read failed): the numbers Approve would send.
     */
    public static NextSend nextSend(AppProperties.FutTransfer cfg, long amountK,
                                    com.globalfutservice.domain.catalog.Platform platform,
                                    FutTransferClient.BuyConditions market) {
        NextSend shape = nextSend(cfg, amountK);
        if (shape.refusal() != null || cfg.orderMode() == AppProperties.FutTransferOrderMode.OWN_SENDERS) {
            return shape;
        }
        PoolThreshold.Result r = PoolThreshold.decide(market, platform, amountK, cfg.publicPool());
        BigDecimal limit = PoolThreshold.pc(platform) ? cfg.publicPool().maxEurPer100kPc()
                : cfg.publicPool().maxEurPer100kConsole();
        return switch (r) {
            case PoolThreshold.Price p -> new NextSend(shape.orderMode(), shape.endpoint(), shape.transferMethod(),
                    p.threshold(), shape.buyNowThresholdSource(), shape.maxPrice(), shape.topUpEnabled(),
                    shape.autoFinishCycle(), shape.senderGroup(), null, shape.minTransferAmount(),
                    shape.pauseIfBelowMinTransfer(), shape.riskLevel(), p.supplierRate(), p.marginPct(), p.limit(),
                    PoolThreshold.estimatedCost(p.supplierRate(), p.transferFee(), amountK),
                    p.fixed() ? "fixed (GFS_FUTTRANSFER_BUY_NOW_THRESHOLD)"
                            : p.cappedAtLimit() ? "supplier rate + margin, capped at your limit"
                            : "supplier rate + margin");
            case PoolThreshold.AboveLimit a -> new NextSend(shape.orderMode(), shape.endpoint(), shape.transferMethod(),
                    null, shape.buyNowThresholdSource(), shape.maxPrice(), shape.topUpEnabled(), shape.autoFinishCycle(),
                    shape.senderGroup(), a.message() + " Approve would send nothing.", shape.minTransferAmount(),
                    shape.pauseIfBelowMinTransfer(), shape.riskLevel(), a.supplierRate(), null, a.limit(),
                    PoolThreshold.estimatedCost(a.supplierRate(), market == null ? null : market.transferFee(), amountK),
                    null);
            case PoolThreshold.Unavailable u -> new NextSend(shape.orderMode(), shape.endpoint(), shape.transferMethod(),
                    null, shape.buyNowThresholdSource(), shape.maxPrice(), shape.topUpEnabled(), shape.autoFinishCycle(),
                    shape.senderGroup(), u.reason() + " Approve would send nothing now; automatic sending tries again "
                    + "shortly.", shape.minTransferAmount(), shape.pauseIfBelowMinTransfer(), shape.riskLevel(), null,
                    null, limit, null, null);
        };
    }

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
        String source = ownSenders ? null : terms.buyNowThreshold() != null
                ? "fixed (GFS_FUTTRANSFER_BUY_NOW_THRESHOLD)"
                : "FUT Transfer's supplier price + " + cfg.publicPool().marginPct().stripTrailingZeros().toPlainString()
                        + "%, read at each send";
        return new NextSend(terms.orderMode(), endpoint, terms.transferMethod(), terms.buyNowThreshold(), source,
                terms.maxPrice(), order.topUpEnabled(), order.autoFinishCycle(), senderGroup, null,
                order.minTransferAmount(), order.pauseIfBelowMinTransfer(), cfg.riskLevel());
    }

    /** How the threshold is set, in words, for the startup line. */
    private static String thresholdRule(AppProperties.FutTransferPublicPool pool) {
        String limits = "limit PlayStation/Xbox " + euro(pool.maxEurPer100kConsole()) + ", PC " + euro(pool.maxEurPer100kPc());
        if (pool.buyNowThreshold() != null) {
            return pool.buyNowThreshold().toPlainString() + " fixed on every order (" + limits + ")";
        }
        return "FUT Transfer's clearing supplier price + " + pool.marginPct().stripTrailingZeros().toPlainString()
                + "%, read at each send (" + limits + ")";
    }

    private static String euro(BigDecimal v) {
        return v == null ? "none" : "€" + v.stripTrailingZeros().toPlainString();
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
                + ", buyNowThreshold (" + THRESHOLD_LABEL + ")=" + thresholdRule(pool)
                + ", minTransferAmount=" + order.minTransferAmount() + ", topUpEnabled=" + order.topUpEnabled()
                + ", autoFinishCycle=" + order.autoFinishCycle() + ", maxPrice "
                + (pool.sendMaxPrice() ? pool.maxPrice().toPlainString() : "not sent") + "; no senderGroup, no supplierID)."
                + (retiredModeSet(cfg) ? " GFS_FUTTRANSFER_BUY_NOW_THRESHOLD_MODE=ORDER_AMOUNT is set but ignored: the "
                + "threshold never comes from the order's amount. Remove the variable." : "");
    }
}
