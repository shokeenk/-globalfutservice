package com.globalfutservice.domain.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * An exact monetary amount held as <b>minor units</b> (paise for INR, cents for USD).
 *
 * <p>Rules enforced by this type:
 * <ul>
 *   <li>Never {@code double} or {@code float} — binary floating point cannot represent
 *       0.1 and will silently drift across a discount chain.</li>
 *   <li>Never "rupees as a decimal in the database" — the column is {@code BIGINT}.</li>
 *   <li>Arithmetic between different currencies throws rather than coercing.</li>
 * </ul>
 *
 * <p>Razorpay's Orders API takes paise as an integer, so this representation maps to the
 * gateway with no conversion step at all.
 */
public record Money(long minor, Currency currency) implements Comparable<Money> {

    public Money {
        Objects.requireNonNull(currency, "currency");
    }

    public static Money ofMinor(long minor, Currency currency) {
        return new Money(minor, currency);
    }

    /** Convenience for tests and seed data: {@code Money.ofMajor("700.00", INR)}. */
    public static Money ofMajor(String major, Currency currency) {
        BigDecimal scaled = new BigDecimal(major)
                .movePointRight(currency.exponent())
                .setScale(0, RoundingMode.HALF_UP);
        return new Money(scaled.longValueExact(), currency);
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.subtractExact(minor, other.minor), currency);
    }

    public Money negated() {
        return new Money(Math.negateExact(minor), currency);
    }

    public Money abs() {
        return minor < 0 ? negated() : this;
    }

    public boolean isZero() {
        return minor == 0L;
    }

    public boolean isNegative() {
        return minor < 0L;
    }

    public boolean isPositive() {
        return minor > 0L;
    }

    /** Exact decimal value in major units — for display and for tax/invoice output only. */
    public BigDecimal toMajor() {
        return BigDecimal.valueOf(minor).movePointLeft(currency.exponent());
    }

    /** Human-readable, e.g. {@code ₹2,584.05}. Grouping is Indian-style for INR. */
    public String format() {
        BigDecimal major = toMajor().abs().setScale(currency.exponent(), RoundingMode.UNNECESSARY);
        String digits = major.toPlainString();
        String intPart = digits;
        String fracPart = "";
        int dot = digits.indexOf('.');
        if (dot >= 0) {
            intPart = digits.substring(0, dot);
            fracPart = digits.substring(dot);
        }
        String grouped = currency == Currency.INR ? groupIndian(intPart) : groupWestern(intPart);
        return (minor < 0 ? "-" : "") + currency.symbol() + grouped + fracPart;
    }

    private static String groupWestern(String s) {
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = s.length() - 1; i >= 0; i--) {
            out.append(s.charAt(i));
            if (++count % 3 == 0 && i > 0) {
                out.append(',');
            }
        }
        return out.reverse().toString();
    }

    /** 1,23,45,678 — the grouping Indian customers expect on an invoice. */
    private static String groupIndian(String s) {
        if (s.length() <= 3) {
            return s;
        }
        String last3 = s.substring(s.length() - 3);
        String rest = s.substring(0, s.length() - 3);
        StringBuilder out = new StringBuilder();
        int count = 0;
        for (int i = rest.length() - 1; i >= 0; i--) {
            out.append(rest.charAt(i));
            if (++count % 2 == 0 && i > 0) {
                out.append(',');
            }
        }
        return out.reverse() + "," + last3;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    private void requireSameCurrency(Money other) {
        if (other.currency != this.currency) {
            throw new IllegalArgumentException(
                    "Currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }

    @Override
    public String toString() {
        return format();
    }
}
