package com.globalfutservice.fulfilment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Platform;

/**
 * The {@code buyNowThreshold} a public-pool order is sent with, worked out from FUT Transfer's
 * own prices just before it is sent.
 *
 * <p>FUT Transfer (2026-10-15): it is a price <b>ceiling in EUR per 100K</b>, a positive value is
 * required, and 0 is not "unlimited". The rate is /buyConditionAPI's public supplier price for
 * the order's platform -- {@code priceConsole} for PlayStation and Xbox, {@code pricePC} for PC.
 *
 * <p>The rule: the public suppliers with stock on that platform, cheapest first; the price at
 * which their combined stock covers the order is the clearing rate; the threshold is that plus
 * the margin (default 10%), rounded to 2 decimals. With a limit for the platform: a clearing rate
 * above it means nothing is sent; a threshold above it only because of the margin is sent at the
 * limit. {@code GFS_FUTTRANSFER_BUY_NOW_THRESHOLD}, when set, is an emergency fixed value instead
 * -- positive, and still within the limit.
 *
 * <p>Never 0, never negative, never missing, never the order's quantity and never our own
 * (rupee) price: the inputs here are FUT Transfer's prices and our settings, nothing else.
 */
public final class PoolThreshold {

    private PoolThreshold() {
    }

    public sealed interface Result permits Price, AboveLimit, Unavailable {
    }

    /**
     * What to send.
     *
     * @param supplierRate  the clearing supplier price, EUR per 100K; null for a fixed override
     *                      when no market price could be read
     * @param marginPct     the margin added, in percent; null for a fixed override
     * @param threshold     the buyNowThreshold to send, EUR per 100K, always positive
     * @param cappedAtLimit the margin took it over the limit, so it is the limit
     * @param fixed         GFS_FUTTRANSFER_BUY_NOW_THRESHOLD, not worked out
     * @param limit         the limit for the platform, or null for none
     * @param transferFee   /buyConditionAPI's transferFee as reported, or null
     */
    public record Price(BigDecimal supplierRate, BigDecimal marginPct, BigDecimal threshold, boolean cappedAtLimit,
                        boolean fixed, BigDecimal limit, BigDecimal transferFee) implements Result {
    }

    /** FUT Transfer's price is above the limit: nothing is sent; the order waits for an admin. */
    public record AboveLimit(BigDecimal supplierRate, BigDecimal limit, String message) implements Result {
    }

    /** No usable price right now: nothing is sent, and it is tried again later. */
    public record Unavailable(String reason) implements Result {
    }

    public static boolean pc(Platform platform) {
        return platform == Platform.PC;
    }

    /**
     * @param market   /buyConditionAPI as read just now for the order's platform, or null if it failed
     * @param amountK  the order, in thousands of coins: only to see whose stock covers it
     */
    public static Result decide(FutTransferClient.BuyConditions market, Platform platform, long amountK,
                                AppProperties.FutTransferPublicPool cfg) {
        boolean pc = pc(platform);
        String side = pc ? "PC" : "PlayStation/Xbox";
        BigDecimal limit = pc ? cfg.maxEurPer100kPc() : cfg.maxEurPer100kConsole();
        BigDecimal transferFee = market == null ? null : market.transferFee();

        if (cfg.buyNowThreshold() != null) {
            BigDecimal fixed = cfg.buyNowThreshold();
            if (fixed.signum() <= 0) {
                return new Unavailable("GFS_FUTTRANSFER_BUY_NOW_THRESHOLD must be above 0.");
            }
            if (limit != null && fixed.compareTo(limit) > 0) {
                return new AboveLimit(null, limit, "GFS_FUTTRANSFER_BUY_NOW_THRESHOLD €" + plain(fixed)
                        + "/100K is above your " + side + " limit €" + plain(limit) + " ("
                        + (pc ? "GFS_FUTTRANSFER_MAX_EUR_PER_100K_PC" : "GFS_FUTTRANSFER_MAX_EUR_PER_100K_CONSOLE")
                        + ").");
            }
            BigDecimal rate = market == null ? null : clearingRate(market.suppliers(), pc, amountK);
            return new Price(rate, null, fixed, false, true, limit, transferFee);
        }

        if (market == null) {
            return new Unavailable("FUT Transfer's prices could not be read (/buyConditionAPI).");
        }
        BigDecimal rate = clearingRate(market.suppliers(), pc, amountK);
        if (rate == null) {
            return new Unavailable("FUT Transfer's public pool has not enough " + side + " stock priced for "
                    + amountK + "K right now (" + stock(market.suppliers(), pc).toPlainString() + "K in stock).");
        }
        if (limit != null && rate.compareTo(limit) > 0) {
            return new AboveLimit(rate, limit, "FUT Transfer price €" + plain(rate) + "/100K is above your limit €"
                    + plain(limit) + ".");
        }
        BigDecimal margin = cfg.marginPct();
        BigDecimal threshold = rate.multiply(BigDecimal.ONE.add(margin.movePointLeft(2)))
                .setScale(2, RoundingMode.HALF_UP);
        boolean capped = false;
        if (limit != null && threshold.compareTo(limit) > 0) {
            threshold = limit.setScale(2, RoundingMode.DOWN);
            capped = true;
        }
        if (threshold.signum() <= 0) {
            return new Unavailable("The threshold worked out from FUT Transfer's prices was not above 0.");
        }
        return new Price(rate, margin, threshold, capped, false, limit, transferFee);
    }

    /**
     * The price at which the cheapest public suppliers' combined stock on this side covers the
     * order: null when their stock together does not.
     */
    static BigDecimal clearingRate(List<FutTransferClient.PoolSupplier> suppliers, boolean pc, long amountK) {
        List<FutTransferClient.PoolSupplier> usable = suppliers.stream()
                .filter(s -> positive(price(s, pc)) && positive(stockOf(s, pc)))
                .sorted(Comparator.comparing(s -> price(s, pc)))
                .toList();
        BigDecimal covered = BigDecimal.ZERO;
        BigDecimal needed = BigDecimal.valueOf(amountK);
        for (FutTransferClient.PoolSupplier s : usable) {
            covered = covered.add(stockOf(s, pc));
            if (covered.compareTo(needed) >= 0) {
                return price(s, pc);
            }
        }
        return null;
    }

    /**
     * What FUT Transfer would charge, roughly, in EUR: the order in 100Ks at the supplier rate,
     * plus the transfer fee once (taken, like the tool fee, as per 100K). Null without a rate.
     */
    public static BigDecimal estimatedCost(BigDecimal supplierRate, BigDecimal transferFee, long amountK) {
        if (supplierRate == null) return null;
        BigDecimal per100k = supplierRate.add(transferFee == null ? BigDecimal.ZERO : transferFee);
        return per100k.multiply(BigDecimal.valueOf(amountK)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal stock(List<FutTransferClient.PoolSupplier> suppliers, boolean pc) {
        return suppliers.stream().filter(s -> positive(price(s, pc))).map(s -> stockOf(s, pc))
                .filter(PoolThreshold::positive).reduce(BigDecimal.ZERO, BigDecimal::add).stripTrailingZeros();
    }

    private static BigDecimal price(FutTransferClient.PoolSupplier s, boolean pc) {
        return pc ? s.pricePc() : s.priceConsole();
    }

    private static BigDecimal stockOf(FutTransferClient.PoolSupplier s, boolean pc) {
        return pc ? s.stockPcK() : s.stockConsoleK();
    }

    private static boolean positive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }
}
