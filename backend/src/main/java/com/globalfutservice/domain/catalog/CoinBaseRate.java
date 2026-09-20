package com.globalfutservice.domain.catalog;

import com.globalfutservice.domain.money.Currency;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one place that knows 100,000 coins is a tenth of the million the rate card stores.
 *
 * <p>The pricing engine multiplies a price <i>per million</i>, because that is the unit
 * quantities arrive in. The business sets a price <i>per 100,000</i>, because that is the
 * unit it sells in. Those two facts have to meet somewhere, and a controller is the wrong
 * place for it: the conversion is arithmetic about money, it is the same in every caller,
 * and getting it wrong by a factor of ten misprices every order without failing anything.
 *
 * <p>Deliberately free of Spring and JPA, like the rest of {@code domain} — see
 * {@link com.globalfutservice.domain.pricing.PricingEngine}.
 */
public final class CoinBaseRate {

    /** 100,000 coins is a tenth of a million. */
    private static final BigDecimal PER_MILLION_PER_100K = BigDecimal.TEN;

    /** The slider's step, as a fraction of a million: 10,000 coins. */
    private static final BigDecimal STEP_FRACTION = new BigDecimal("0.01");

    private CoinBaseRate() {
    }

    /**
     * A price per 100,000 coins, in major units, as minor units per million.
     *
     * <p>Rs.1,600.00 becomes 1,600,000 paise; $16.70 becomes 16,700 cents.
     *
     * @throws IllegalArgumentException if the price is not positive, or is finer than the
     *         currency can store per million. Rejected rather than rounded: quietly
     *         turning EUR 14.5555 into EUR 145.55 per million would make the screen report a
     *         price the owner did not set, which is the one thing a price field must
     *         never do.
     */
    public static long toPerMillionMinor(Currency currency, BigDecimal per100k) {
        if (per100k == null) {
            throw new IllegalArgumentException("A price is required for " + currency + ".");
        }
        if (per100k.signum() <= 0) {
            throw new IllegalArgumentException(
                    "The " + currency + " price must be greater than zero.");
        }
        BigDecimal minor = per100k
                .multiply(PER_MILLION_PER_100K)
                .multiply(BigDecimal.valueOf(currency.minorPerMajor()));
        try {
            return minor.setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(
                    "The " + currency + " price is finer than this currency can store. "
                            + "100,000 coins must price to a whole amount per million.");
        }
    }

    /** What the owner set: the price of 100,000 coins, in major units. */
    public static BigDecimal per100k(Currency currency, long perMillionMinor) {
        return majorPerMillion(currency, perMillionMinor)
                .divide(PER_MILLION_PER_100K, currency.exponent() + 1, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    /**
     * What one 10,000-coin slider step is worth, in major units.
     *
     * <p>May carry a fraction of a minor unit — EUR 145.50 per million is EUR 1.455 a step.
     * That is reported rather than hidden; see {@link #stepIsWholeMinorUnit}.
     */
    public static BigDecimal per10k(Currency currency, long perMillionMinor) {
        return stepMinor(perMillionMinor)
                .divide(BigDecimal.valueOf(currency.minorPerMajor()),
                        currency.exponent() + 2, RoundingMode.HALF_UP)
                .stripTrailingZeros();
    }

    /**
     * Whether a 10,000-coin step lands on a whole minor unit.
     *
     * <p>False does not mean the price is inexact. The engine multiplies the whole
     * quantity in exact decimal and rounds once, at the total, so the charge is never more
     * than half a minor unit from the exact figure and the error never accumulates. It
     * means the <i>displayed gap</i> between adjacent steps alternates by one, which
     * somebody will eventually ask about — so the admin screen says it in advance.
     */
    public static boolean stepIsWholeMinorUnit(long perMillionMinor) {
        return stepMinor(perMillionMinor).stripTrailingZeros().scale() <= 0;
    }

    private static BigDecimal stepMinor(long perMillionMinor) {
        return BigDecimal.valueOf(perMillionMinor).multiply(STEP_FRACTION);
    }

    private static BigDecimal majorPerMillion(Currency currency, long perMillionMinor) {
        return BigDecimal.valueOf(perMillionMinor)
                .divide(BigDecimal.valueOf(currency.minorPerMajor()),
                        currency.exponent(), RoundingMode.UNNECESSARY);
    }
}
