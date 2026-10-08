package com.globalfutservice.domain.catalog;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeSet;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;

/**
 * One version of a coin price structure: the slider, and what the coins cost.
 *
 * <p>Amounts are whole thousands of coins throughout ({@code 50} is 50K), because that is
 * the unit FUT Transfer takes and the unit the slider moves in. The pricing engine still
 * multiplies millions; {@link #rateCard} is where the two meet.
 *
 * <p><b>Whole-order brackets.</b> Per currency there is a base rate per million and,
 * optionally, brackets: from a bracket's amount on, every coin in the order is priced at
 * its rate. "From 500K, ₹12,000 per 1M" makes a 600K order cost 600K at ₹12,000. Simple to
 * state and to show, and it means an amount just below a bracket can cost more than the
 * bracket's own first amount. That is allowed -- it is the business's choice -- but
 * {@link #check} warns about it with the amounts and prices.
 *
 * <p>Free of Spring and JPA, like the rest of {@code domain}.
 *
 * @param version     the stored version's id; null for a draft that has not been saved
 * @param quickPicksK the amounts offered as one-tap buttons, in order
 * @param rates       per currency, the base rate ({@code fromK} 0) and any brackets
 */
public record CoinPriceTable(
        Long version,
        String season,
        CoinMarket market,
        int minK,
        int maxK,
        int stepK,
        List<Integer> quickPicksK,
        Map<Currency, List<Bracket>> rates) {

    /** The finest step: orders store coins in millions to two decimal places. */
    public static final int SMALLEST_STEP_K = 10;
    /** The most one order can be: 10M. */
    public static final int MAX_K_CAP = 10_000;
    public static final int MAX_QUICK_PICKS = 8;
    public static final int MAX_BRACKETS = 10;
    /** The quick picks a structure starts with: the storefront's set before they were configurable. */
    public static final List<Integer> DEFAULT_QUICK_PICKS_K = List.of(50, 100, 250, 500, 1_000);

    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1000);

    /** A rate per million from {@code fromK} on; {@code fromK} 0 is the base rate. */
    public record Bracket(int fromK, long perMillionMinor) {
    }

    public CoinPriceTable {
        quickPicksK = List.copyOf(quickPicksK);
        Map<Currency, List<Bracket>> sorted = new EnumMap<>(Currency.class);
        rates.forEach((currency, brackets) -> sorted.put(currency, brackets.stream()
                .sorted(Comparator.comparingInt(Bracket::fromK)).toList()));
        rates = java.util.Collections.unmodifiableMap(sorted);
    }

    /** Whether there is a price for {@code currency}: a base rate. */
    public boolean prices(Currency currency) {
        return base(currency).isPresent();
    }

    /** The base rate per million, in minor units. */
    public OptionalLong base(Currency currency) {
        return rates.getOrDefault(currency, List.of()).stream()
                .filter(b -> b.fromK() == 0)
                .mapToLong(Bracket::perMillionMinor)
                .findFirst();
    }

    /** The brackets above the base, lowest first. */
    public List<Bracket> brackets(Currency currency) {
        return rates.getOrDefault(currency, List.of()).stream().filter(b -> b.fromK() > 0).toList();
    }

    /** The rate an order of {@code amountK} pays per million: the highest bracket it reaches. */
    public OptionalLong perMillionMinor(Currency currency, int amountK) {
        if (!prices(currency)) {
            return OptionalLong.empty();
        }
        long rate = base(currency).getAsLong();
        for (Bracket b : brackets(currency)) {
            if (b.fromK() <= amountK) {
                rate = b.perMillionMinor();
            }
        }
        return OptionalLong.of(rate);
    }

    /** What {@code amountK} coins cost before tax, discounts and fees, exactly, in minor units. */
    public BigDecimal coinPriceExact(Currency currency, int amountK) {
        long rate = perMillionMinor(currency, amountK).orElseThrow();
        return BigDecimal.valueOf(rate).multiply(BigDecimal.valueOf(amountK)).divide(THOUSAND);
    }

    /** {@link #coinPriceExact}, rounded to a payable amount. */
    public Money coinPrice(Currency currency, int amountK) {
        return Money.ofMinor(coinPriceExact(currency, amountK).setScale(0, RoundingMode.HALF_UP)
                .longValueExact(), currency);
    }

    /**
     * The row the pricing engine prices this order from: the rate for the amount asked for,
     * and the structure's range in the millions the engine works in. The engine checks the
     * amount against the range; this only picks the rate.
     *
     * @param quantityMillions the amount asked for, in millions
     * @return null when there is no price in {@code currency}
     */
    public RateCard rateCard(Platform platform, Currency currency, BigDecimal quantityMillions) {
        int amountK = quantityMillions == null ? minK : quantityMillions.multiply(THOUSAND)
                .setScale(0, RoundingMode.DOWN).min(BigDecimal.valueOf(Integer.MAX_VALUE)).intValue();
        OptionalLong rate = perMillionMinor(currency, amountK);
        if (rate.isEmpty()) {
            return null;
        }
        return new RateCard(season, Sku.TRADING_SERVICE, platform, null, platform.displayName(),
                Money.ofMinor(rate.getAsLong(), currency), millions(minK), millions(maxK), millions(stepK), version);
    }

    /** Thousands to the millions the engine and the order store: 50 is 0.05. */
    public static BigDecimal millions(int k) {
        return BigDecimal.valueOf(k).divide(THOUSAND).setScale(2, RoundingMode.UNNECESSARY);
    }

    /** "50K", "1M", "1.5M". */
    public static String describeK(int k) {
        return CoinAmount.describe(BigDecimal.valueOf(k).divide(THOUSAND));
    }

    // ------------------------------------------------------------------ checks

    /** What would stop this being saved, and what the admin should know before saving it. */
    public record Check(List<String> errors, List<String> warnings) {
        public boolean ok() {
            return errors.isEmpty();
        }
    }

    /**
     * Checks a version before it is saved.
     *
     * <p>Errors stop a save: a range or a price the shop cannot sell. Warnings do not: they
     * are pricing judgements the business is entitled to make, said out loud first.
     *
     * @param required            every currency the shop sells in: each needs a base rate
     * @param vendorMinTransferK  FUT Transfer's minimum per transfer, from its settings
     */
    public Check check(Collection<Currency> required, int vendorMinTransferK) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        boolean rangeOk = true;
        if (stepK < SMALLEST_STEP_K || stepK % SMALLEST_STEP_K != 0) {
            errors.add("The step must be a whole multiple of 10K, at least 10K.");
            rangeOk = false;
        }
        if (minK < SMALLEST_STEP_K || minK % SMALLEST_STEP_K != 0) {
            errors.add("The minimum must be a whole multiple of 10K, at least 10K.");
            rangeOk = false;
        }
        if (maxK > MAX_K_CAP) {
            errors.add("The maximum can be at most " + describeK(MAX_K_CAP) + ".");
            rangeOk = false;
        }
        if (maxK < minK) {
            errors.add("The maximum must be at least the minimum.");
            rangeOk = false;
        }
        if (rangeOk && (maxK - minK) % stepK != 0) {
            int below = maxK - (maxK - minK) % stepK;
            errors.add("The maximum must be the minimum plus whole steps of " + describeK(stepK) + ": "
                    + describeK(below) + " or " + describeK(below + stepK) + ", not " + describeK(maxK) + ".");
            rangeOk = false;
        }

        if (quickPicksK.size() > MAX_QUICK_PICKS) {
            errors.add("At most " + MAX_QUICK_PICKS + " quick picks.");
        }
        Set<Integer> seenPicks = new HashSet<>();
        for (int k : quickPicksK) {
            if (!seenPicks.add(k)) {
                errors.add("Quick pick " + describeK(k) + " is listed twice.");
            } else if (rangeOk) {
                onTheSlider("Quick pick", k, true).ifPresent(errors::add);
            }
        }

        Set<Currency> currencies = new TreeSet<>(rates.keySet());
        currencies.addAll(required);
        for (Currency currency : currencies) {
            List<Bracket> all = rates.getOrDefault(currency, List.of());
            if (all.stream().noneMatch(b -> b.fromK() == 0)) {
                errors.add("Set a base price for " + currency + ".");
                continue;
            }
            if (all.stream().anyMatch(b -> b.perMillionMinor() <= 0)) {
                errors.add("Every " + currency + " price must be greater than zero.");
                continue;
            }
            List<Bracket> brackets = brackets(currency);
            if (brackets.size() > MAX_BRACKETS) {
                errors.add("At most " + MAX_BRACKETS + " brackets for " + currency + ".");
            }
            Set<Integer> seen = new HashSet<>();
            boolean bracketsOk = true;
            for (Bracket b : brackets) {
                if (!seen.add(b.fromK())) {
                    errors.add(currency + ": two brackets start at " + describeK(b.fromK()) + ".");
                    bracketsOk = false;
                } else if (rangeOk) {
                    if (b.fromK() <= minK) {
                        errors.add(currency + ": a bracket must start above the minimum (" + describeK(minK)
                                + "); the base price covers the minimum. " + describeK(b.fromK()) + " does not.");
                        bracketsOk = false;
                    } else {
                        var problem = onTheSlider(currency + ": the bracket from", b.fromK(), false);
                        if (problem.isPresent()) {
                            errors.add(problem.get());
                            bracketsOk = false;
                        }
                    }
                }
            }
            if (rangeOk && bracketsOk) {
                warnings.addAll(bracketWarnings(currency));
            }
        }

        if (rangeOk && minK < vendorMinTransferK) {
            warnings.add("Orders below " + describeK(vendorMinTransferK) + " may not deliver properly with the "
                    + "current FUT Transfer settings: its minimum per transfer is " + describeK(vendorMinTransferK)
                    + ", and this minimum is " + describeK(minK) + ".");
        }
        return new Check(List.copyOf(errors), List.copyOf(warnings));
    }

    /** Why {@code k} is not an amount the slider can stop on, if it is not. */
    private java.util.Optional<String> onTheSlider(String what, int k, boolean minIncluded) {
        if (k < minK || (!minIncluded && k == minK)) {
            return java.util.Optional.of(what + " " + describeK(k) + " is below the minimum (" + describeK(minK) + ").");
        }
        if (k > maxK) {
            return java.util.Optional.of(what + " " + describeK(k) + " is above the maximum (" + describeK(maxK) + ").");
        }
        if ((k - minK) % stepK != 0) {
            int below = k - (k - minK) % stepK;
            return java.util.Optional.of(what + " " + describeK(k) + " is not on a step: the slider goes "
                    + describeK(below) + ", " + describeK(below + stepK) + "…");
        }
        return java.util.Optional.empty();
    }

    /**
     * Where a bracket makes more coins cost less, and where a bracket raises the rate.
     *
     * <p>Whole-order brackets: at a bracket's first amount every coin takes the lower rate,
     * so some amounts just below it cost more. Each is named with the two amounts and
     * prices: the bracket's first amount, and the step just below it.
     */
    private List<String> bracketWarnings(Currency currency) {
        List<String> out = new ArrayList<>();
        long previousRate = base(currency).getAsLong();
        for (Bracket b : brackets(currency)) {
            if (b.perMillionMinor() > previousRate) {
                out.add(currency + ": from " + describeK(b.fromK()) + " the rate goes up, from "
                        + per100k(currency, previousRate) + " to " + per100k(currency, b.perMillionMinor())
                        + " per 100K (" + perMillion(currency, previousRate) + " to "
                        + perMillion(currency, b.perMillionMinor()) + " per 1M).");
            }
            previousRate = b.perMillionMinor();

            BigDecimal atEdge = coinPriceExact(currency, b.fromK());
            Integer highest = null;
            Integer lowest = null;
            for (int k = minK; k < b.fromK(); k += stepK) {
                if (coinPriceExact(currency, k).compareTo(atEdge) > 0) {
                    if (lowest == null) lowest = k;
                    highest = k;
                }
            }
            if (highest != null) {
                String message = currency + ": " + describeK(b.fromK()) + " costs " + coinPrice(currency, b.fromK()).format()
                        + ", less than " + describeK(highest) + " at " + coinPrice(currency, highest).format() + ".";
                if (lowest < highest) {
                    message += " Every amount from " + describeK(lowest) + " to " + describeK(highest)
                            + " costs more than " + describeK(b.fromK()) + ".";
                }
                out.add(message);
            }
        }
        return out;
    }

    /** A rate per million as the admin types it: per 100,000 coins. */
    public static String per100k(Currency currency, long perMillionMinor) {
        if (perMillionMinor % 10 == 0) {
            return Money.ofMinor(perMillionMinor / 10, currency).format();
        }
        return currency.symbol() + CoinBaseRate.per100k(currency, perMillionMinor).toPlainString();
    }

    /** A rate per million, formatted. */
    public static String perMillion(Currency currency, long perMillionMinor) {
        return Money.ofMinor(perMillionMinor, currency).format();
    }
}
