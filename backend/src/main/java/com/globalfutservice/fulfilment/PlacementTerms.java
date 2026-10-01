package com.globalfutservice.fulfilment;

import java.math.BigDecimal;

import com.globalfutservice.config.AppProperties;

/**
 * How one coin order is to be placed, decided from configuration at the moment it is sent.
 *
 * <p>Asked on every Approve, first send or retry alike, so a retry follows the
 * configuration of the moment and the ledger records what that was.
 *
 * <p>A setting that is needed and missing does not stop the application -- that would take
 * the shop down over one partner setting -- it stops the send, with a reason the admin can
 * act on. Malformed values never get this far: they fail at startup.
 */
final class PlacementTerms {

    /** The only method public-pool orders are built for: no supplier, a buy-now threshold. */
    static final String POOL_METHOD = "targetedSnipe";

    private PlacementTerms() {
    }

    sealed interface Decision permits Send, Refuse {
    }

    record Send(VendorOrderLedger.SendTerms terms) implements Decision {
    }

    /** Not sent. {@code reason} is for the admin and never contains a credential. */
    record Refuse(String reason) implements Decision {
    }

    static Decision decide(AppProperties.FutTransfer cfg, long amountK) {
        if (cfg.orderMode() == AppProperties.FutTransferOrderMode.OWN_SENDERS) {
            return new Send(new VendorOrderLedger.SendTerms("OWN_SENDERS", cfg.transferMethod(), null, null));
        }

        if (!POOL_METHOD.equals(cfg.transferMethod())) {
            return new Refuse("Public-pool orders are sent as " + POOL_METHOD + ", but GFS_FUTTRANSFER_METHOD is "
                    + cfg.transferMethod() + ". Set it back to " + POOL_METHOD + ", or set GFS_FUTTRANSFER_ORDER_MODE "
                    + "to OWN_SENDERS. Nothing was sent.");
        }

        AppProperties.FutTransferPublicPool pool = cfg.publicPool();
        BigDecimal threshold = switch (pool.buyNowThresholdMode()) {
            // The client's decision: the order's coins in K, the same number as `amount`.
            case ORDER_AMOUNT -> BigDecimal.valueOf(amountK);
            case FIXED -> pool.buyNowThreshold();
        };
        if (threshold == null) {
            return new Refuse("GFS_FUTTRANSFER_BUY_NOW_THRESHOLD_MODE is FIXED, but GFS_FUTTRANSFER_BUY_NOW_THRESHOLD "
                    + "is not set. Set it, or switch the mode back to ORDER_AMOUNT. Nothing was sent.");
        }

        BigDecimal maxPrice = null;
        if (pool.sendMaxPrice()) {
            if (pool.maxPrice() == null) {
                return new Refuse("GFS_FUTTRANSFER_SEND_MAX_PRICE is true, but GFS_FUTTRANSFER_MAX_PRICE is not set. "
                        + "Set it, or turn sending maxPrice off. Nothing was sent.");
            }
            maxPrice = pool.maxPrice();
        }
        return new Send(new VendorOrderLedger.SendTerms("PUBLIC_POOL", POOL_METHOD, threshold, maxPrice));
    }
}
