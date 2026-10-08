package com.globalfutservice.catalog;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Whether the configured season has anything to sell, checked once at startup.
 *
 * <p>Every price the storefront shows is looked up by {@code GFS_SEASON}. Point it at a
 * season with no rate cards and nothing fails: the catalogue answers 200 with every
 * service empty, the order pages have nothing to quote, and no error is logged anywhere.
 * That is how production lost checkout when the season was moved to FC27 before any FC27
 * prices existed -- the coin page sat still and the logs were clean.
 *
 * <p>So a season with nothing on sale refuses to start. A host that keeps the last good
 * deploy running when a new one fails turns a wrong season into a failed deploy rather
 * than an outage. A season that is only partly priced -- coaching missing in euros, say --
 * still starts, because prices are entered over time, but every gap is logged as an error
 * naming the season, the service and the currency.
 */
@Component
public class SeasonPriceCheck {

    private static final Logger log = LoggerFactory.getLogger(SeasonPriceCheck.class);

    private final RateCardRepository rates;
    private final AppProperties props;
    private final CoinPricingService coins;

    public SeasonPriceCheck(RateCardRepository rates, AppProperties props, CoinPricingService coins) {
        this.rates = rates;
        this.props = props;
        this.coins = coins;
    }

    /** Throws when the season has nothing to sell; logs any partial gaps. */
    public void verify() {
        String season = props.season();
        Map<Currency, Set<Sku>> priced = new LinkedHashMap<>();
        // Coins are on sale in a currency when every coin structure prices it; a coin row
        // still live on the rate card is not what quotes read, so it does not count.
        Set<Currency> coinPriced = coins.pricedCurrencies(season);
        for (Currency currency : currencies(props.pricing().enabledCurrencies())) {
            Set<Sku> skus = rates.findLiveForSeason(season, currency).stream()
                    .map(RateCardEntity::getSku)
                    .filter(sku -> sku != Sku.TRADING_SERVICE)
                    .collect(Collectors.toCollection(() -> EnumSet.noneOf(Sku.class)));
            if (coinPriced.contains(currency)) {
                skus.add(Sku.TRADING_SERVICE);
            }
            priced.put(currency, skus);
        }

        List<String> gaps = gaps(season, priced, () -> java.util.stream.Stream
                .concat(rates.findLiveSeasons().stream(), coins.liveSeasons().stream())
                .distinct().sorted().toList());
        if (!gaps.isEmpty()) {
            log.error("\nSeason {} is only partly priced. Customers cannot buy these until a "
                    + "price exists:\n{}", season, String.join("\n", gaps));
        }
    }

    /**
     * The currencies the storefront can offer, in the order they were configured.
     *
     * <p>Same rule as {@link CatalogService#availableCurrencies()}: an allow-list naming
     * nothing usable falls back to rupees, so that is what gets checked.
     */
    static List<Currency> currencies(List<String> enabled) {
        List<Currency> out = enabled.stream()
                .filter(name -> Arrays.stream(Currency.values()).anyMatch(c -> c.name().equals(name)))
                .map(Currency::valueOf)
                .distinct()
                .toList();
        return out.isEmpty() ? List.of(Currency.INR) : out;
    }

    /**
     * Every sellable service missing a price, one line each.
     *
     * @param priced       for each currency checked, the services with a live row
     * @param liveSeasons  asked only when refusing, to say which seasons would work
     * @throws IllegalStateException when no currency has a price for any sellable service
     */
    static List<String> gaps(String season, Map<Currency, Set<Sku>> priced,
                             Supplier<List<String>> liveSeasons) {
        List<Sku> sellable = Arrays.stream(Sku.values()).filter(Sku::sellable).toList();

        boolean anythingOnSale = priced.values().stream()
                .anyMatch(skus -> skus.stream().anyMatch(Sku::sellable));
        if (!anythingOnSale) {
            List<String> elsewhere = liveSeasons.get().stream()
                    .filter(s -> !s.equals(season)).toList();
            throw new IllegalStateException("""

                    ================================================================
                     GFS_SEASON is %s, but nothing is priced for %s.
                     No live rate card exists for any service in %s.

                     Every price is looked up by season, so the shop would open with
                     nothing to sell and nothing in the logs. Refusing to start.

                     Seasons with live prices: %s
                     Set GFS_SEASON to one of those, or add %s rate cards first.
                    ================================================================
                    """.formatted(season, season,
                    priced.keySet().stream().map(Enum::name).collect(Collectors.joining(", ")),
                    elsewhere.isEmpty() ? "none" : String.join(", ", elsewhere),
                    season));
        }

        List<String> gaps = new ArrayList<>();
        priced.forEach((currency, skus) -> {
            if (skus.stream().noneMatch(Sku::sellable)) {
                gaps.add("  - nothing is priced in " + currency);
                return;
            }
            for (Sku sku : sellable) {
                if (!skus.contains(sku)) gaps.add("  - " + sku + " in " + currency);
            }
        });
        return gaps;
    }
}
