package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import com.globalfutservice.domain.money.Currency;

/**
 * What a Payop method adds to an order, so the client receives his full price.
 *
 * <p>Payop takes a fixed part, quoted in EUR, plus a percentage of what is paid. Grossed up:
 *
 * <pre>
 *   total = (net + fixed in the order's currency) / (1 - percentage)
 *   fee   = total - net
 * </pre>
 *
 * <p>The total is rounded <b>up</b> to the currency's smallest unit -- rounding down would
 * leave the client a fraction short on every payment -- and the fee is whatever that leaves.
 * The fixed part is converted with the rate given, which the caller records with the
 * invoice. {@code net} is the price after coupon, discounts and points, and without the 2.5%
 * card fee: a Payop payment carries only its own method's fee.
 *
 * <p>Pure arithmetic: no clock, no database, no Payop.
 */
public final class PayopFeeCalculator {

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PayopFeeCalculator() {
    }

    /**
     * @param netMinor  the price before the fee, in the order currency's smallest unit
     * @param fixedEur  the method's fixed part, in EUR
     * @param percent   the method's percentage, e.g. 4.0 for 4%
     * @param eurToCurrency how many units of the order's currency one EUR buys; 1 for EUR
     */
    public static FeeQuote quote(long netMinor, Currency currency, BigDecimal fixedEur, BigDecimal percent,
                                 BigDecimal eurToCurrency) {
        if (netMinor <= 0) {
            throw new IllegalArgumentException("nothing to charge");
        }
        if (fixedEur.signum() < 0 || percent.signum() < 0 || percent.compareTo(HUNDRED) >= 0) {
            throw new IllegalArgumentException("fee out of range");
        }
        if (eurToCurrency.signum() <= 0) {
            throw new IllegalArgumentException("rate must be positive");
        }
        BigDecimal fixedMinor = fixedEur.multiply(eurToCurrency, MC)
                .multiply(BigDecimal.valueOf(currency.minorPerMajor()), MC);
        BigDecimal keep = BigDecimal.ONE.subtract(percent.divide(HUNDRED, MC), MC);
        BigDecimal exact = BigDecimal.valueOf(netMinor).add(fixedMinor, MC).divide(keep, MC);
        long totalMinor = exact.setScale(0, RoundingMode.CEILING).longValueExact();
        return new FeeQuote(netMinor, totalMinor - netMinor, totalMinor);
    }

    /**
     * The order's total as Payop's {@code order.amount}: major units with exactly the
     * currency's decimals, "12.30", never "12.3" -- the signature is computed on this string.
     */
    public static String amount(long minor, Currency currency) {
        return BigDecimal.valueOf(minor, currency.exponent()).toPlainString();
    }

    /** Amounts in the order currency's smallest unit; {@code fee + net == total} always. */
    public record FeeQuote(long netMinor, long feeMinor, long totalMinor) {
    }
}
