package com.globalfutservice.catalog;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.globalfutservice.catalog.web.CatalogDtos;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.CoinMarket;
import com.globalfutservice.domain.catalog.CoinPriceTable;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Coin prices: PC on its own, PlayStation and Xbox together, for the configured season.
 *
 * <p>What a quote, the catalogue and the admin read and write. A price saved here applies
 * to the next quote; there is nothing to deploy and nothing cached on the server.
 */
@Service
public class CoinPricingService {

    private static final Logger log = LoggerFactory.getLogger(CoinPricingService.class);

    private final CoinPriceStore store;
    private final AppProperties props;

    public CoinPricingService(CoinPriceStore store, AppProperties props) {
        this.store = store;
        this.props = props;
    }

    /** Each structure's live version in the configured season. */
    public Map<CoinMarket, CoinPriceStore.Version> live() {
        return store.live(props.season());
    }

    /** The live prices for {@code platform}'s structure. */
    public Optional<CoinPriceTable> live(Platform platform) {
        return store.live(props.season(), CoinMarket.of(platform)).map(CoinPriceStore.Version::table);
    }

    /**
     * The row the pricing engine prices a coin quote from: {@code platform}'s structure, at
     * the rate the amount reaches.
     */
    public RateCard rateCard(Platform platform, Currency currency, BigDecimal quantity) {
        RateCard card = live(platform).map(t -> t.rateCard(platform, currency, quantity)).orElse(null);
        if (card == null) {
            throw new ApiExceptions.NotFoundException("That option is not available right now.");
        }
        return card;
    }

    /**
     * The catalogue's coin options in {@code currency}: one per platform, in the picker's
     * order, each carrying its structure. PlayStation and Xbox show the same prices because
     * they are the same prices; the customer still picks theirs.
     */
    public List<CatalogDtos.CatalogOption> catalogOptions(Currency currency) {
        Map<CoinMarket, CoinPriceStore.Version> live = live();
        List<CatalogDtos.CatalogOption> out = new ArrayList<>();
        for (Platform platform : List.of(Platform.PC, Platform.PLAYSTATION, Platform.XBOX)) {
            CoinPriceStore.Version version = live.get(CoinMarket.of(platform));
            if (version == null || !version.table().prices(currency)) {
                continue;
            }
            CoinPriceTable t = version.table();
            long base = t.base(currency).getAsLong();
            out.add(new CatalogDtos.CatalogOption(platform.name(), null, platform.displayName(), base,
                    Money.ofMinor(base, currency).format(),
                    CoinPriceTable.millions(t.minK()), CoinPriceTable.millions(t.maxK()),
                    CoinPriceTable.millions(t.stepK()), null, false, details(t, currency)));
        }
        return out;
    }

    private static CatalogDtos.CoinDetails details(CoinPriceTable t, Currency currency) {
        List<CatalogDtos.CoinRate> rates = new ArrayList<>();
        rates.add(rate(currency, 0, t.base(currency).getAsLong()));
        for (CoinPriceTable.Bracket b : t.brackets(currency)) {
            rates.add(rate(currency, b.fromK(), b.perMillionMinor()));
        }
        return new CatalogDtos.CoinDetails(t.market().name(), t.market().displayName(), t.minK(), t.maxK(), t.stepK(),
                t.quickPicksK(), rates);
    }

    private static CatalogDtos.CoinRate rate(Currency currency, int fromK, long perMillionMinor) {
        return new CatalogDtos.CoinRate(fromK, perMillionMinor, CoinPriceTable.perMillion(currency, perMillionMinor),
                CoinPriceTable.per100k(currency, perMillionMinor));
    }

    /** The currencies every structure prices in, in {@code season}: where coins are on sale. */
    public Set<Currency> pricedCurrencies(String season) {
        Map<CoinMarket, CoinPriceStore.Version> live = store.live(season);
        Set<Currency> out = EnumSet.noneOf(Currency.class);
        if (live.size() < CoinMarket.values().length) {
            return out;
        }
        for (Currency currency : Currency.values()) {
            if (live.values().stream().allMatch(v -> v.table().prices(currency))) {
                out.add(currency);
            }
        }
        return out;
    }

    public List<String> liveSeasons() {
        return store.liveSeasons();
    }

    // ------------------------------------------------------------------ admin

    /** Every currency the shop sells in: each structure needs a price in all of them. */
    public List<Currency> requiredCurrencies() {
        return SeasonPriceCheck.currencies(props.pricing().enabledCurrencies());
    }

    /** FUT Transfer's minimum per transfer, in K, as it is configured now. */
    public int vendorMinTransferK() {
        return props.futTransfer().order().minTransferAmount();
    }

    public CoinPriceTable.Check check(CoinPriceTable draft) {
        return draft.check(requiredCurrencies(), vendorMinTransferK());
    }

    public List<CoinPriceStore.Version> history(CoinMarket market, int limit) {
        return store.history(props.season(), market, limit);
    }

    /**
     * Makes {@code draft} the live version of its structure, unless it changes nothing.
     *
     * @throws ApiExceptions.BadRequestException with every error, when it cannot be sold
     * @throws ApiExceptions.ConflictException   when someone else saved first
     */
    @Transactional
    public CoinPriceStore.Version save(CoinPriceTable draft, Long adminId, String adminLabel) {
        CoinPriceTable.Check check = check(draft);
        if (!check.ok()) {
            throw new ApiExceptions.BadRequestException("invalid_coin_pricing", String.join("\n", check.errors()));
        }
        Optional<CoinPriceStore.Version> current = store.live(props.season(), draft.market());
        if (current.isPresent() && sameAs(current.get().table(), draft)) {
            // No-op saves would litter the history and make it useless.
            return current.get();
        }
        try {
            CoinPriceStore.Version saved = store.replace(draft, adminId);
            log.info("Admin {} set {} coin prices for {}: {}K-{}K in {}K steps, version {}", adminLabel,
                    draft.market(), draft.season(), draft.minK(), draft.maxK(), draft.stepK(),
                    saved.table().version());
            return saved;
        } catch (DuplicateKeyException e) {
            throw new ApiExceptions.ConflictException("coin_pricing_changed",
                    "Someone else saved these prices a moment ago. Reload to see theirs before saving yours.");
        }
    }

    private static boolean sameAs(CoinPriceTable a, CoinPriceTable b) {
        return a.minK() == b.minK() && a.maxK() == b.maxK() && a.stepK() == b.stepK()
                && a.quickPicksK().equals(b.quickPicksK()) && a.rates().equals(b.rates());
    }
}
