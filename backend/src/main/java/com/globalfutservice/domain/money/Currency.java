package com.globalfutservice.domain.money;

/**
 * Supported settlement currencies.
 *
 * <p>Prices are <b>authored</b> per currency in the rate card — never FX-converted at
 * runtime. Runtime conversion produces prices like 58,133.41 and a fresh rounding bug on
 * every request. See {@code docs/PRICING.md}.
 */
public enum Currency {
    INR("₹", 2),
    USD("$", 2),
    EUR("€", 2),
    GBP("£", 2),
    AED("AED ", 2);

    private final String symbol;
    private final int exponent;

    Currency(String symbol, int exponent) {
        this.symbol = symbol;
        this.exponent = exponent;
    }

    /** Number of decimal places, i.e. minor units per major unit is 10^exponent. */
    public int exponent() {
        return exponent;
    }

    public String symbol() {
        return symbol;
    }

    public long minorPerMajor() {
        long m = 1;
        for (int i = 0; i < exponent; i++) {
            m *= 10;
        }
        return m;
    }
}
