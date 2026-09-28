package com.globalfutservice.catalog;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The Listings page: boosting tiers and coaching packages, their prices in every
 * currency, whether they are on sale, and for boosting their success rate and Best Value.
 *
 * <p><b>A price change is still an insert.</b> The live row is closed and a new one opened,
 * exactly as the rate card has always worked, so an order placed last month can still be
 * explained and orders already placed keep the price they were placed at: an order freezes
 * its total and breakdown when it is created.
 *
 * <p><b>Hiding is closing.</b> A hidden listing has no live price in any currency, so the
 * catalogue does not offer it; the last prices are kept so turning it back on restores them.
 *
 * <p>Coins are not here: they are priced on the Coin rates page.
 */
@Service
public class ListingService {

    private static final Logger log = LoggerFactory.getLogger(ListingService.class);

    /** The services the page covers, in the order it shows them. */
    public static final List<Sku> SERVICES = List.of(Sku.BOOST_CHAMPS, Sku.BOOST_RIVALS, Sku.COACHING);
    static final Set<Sku> BOOSTING = EnumSet.of(Sku.BOOST_CHAMPS, Sku.BOOST_RIVALS);

    /** Enough for any real price in any currency; stops a slipped zero, not a business. */
    static final long MAX_PRICE_MINOR = 10_000_000L;

    private final RateCardRepository rates;
    private final ListingSettings settings;
    private final AppProperties props;
    private final Clock clock;

    public ListingService(RateCardRepository rates, ListingSettings settings, AppProperties props, Clock clock) {
        this.rates = rates;
        this.settings = settings;
        this.props = props;
        this.clock = clock;
    }

    public record Price(long minor, String formatted) {
    }

    public record Listing(String sku, String variant, String label, int sortOrder, boolean active,
                          /** Live prices; for a hidden listing, the prices it had when hidden. */
                          Map<String, Price> prices,
                          /** Boosting only. */
                          Integer successRateBps,
                          /** Where the rate comes from: LISTING (set here), CONFIGURATION, or null. */
                          String successRateSource,
                          boolean bestValue) {
    }

    public record Category(String sku, String name, List<Listing> listings,
                           /** DEFAULT (the last tier), CHOSEN, or NONE. Boosting only. */
                           String bestValueChoice) {
    }

    public record Overview(List<String> currencies, List<Category> categories) {
    }

    public List<Currency> currencies() {
        List<Currency> out = new ArrayList<>();
        for (String code : props.pricing().enabledCurrencies()) {
            try {
                out.add(Currency.valueOf(code.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // An unknown code in configuration is not a listing's problem.
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public Overview overview() {
        List<Category> categories = new ArrayList<>();
        for (Sku sku : SERVICES) {
            categories.add(category(sku));
        }
        return new Overview(currencies().stream().map(Enum::name).toList(), categories);
    }

    private Category category(Sku sku) {
        String season = props.season();
        Map<String, ListingSettings.Stored> stored = settings.forSku(sku);

        // Live rows in every currency, grouped by listing, in the storefront's order.
        Map<String, List<RateCardEntity>> live = new LinkedHashMap<>();
        for (Currency currency : Currency.values()) {
            for (RateCardEntity row : rates.findLiveForSeason(season, currency)) {
                if (row.getSku() == sku && row.getVariant() != null) {
                    live.computeIfAbsent(row.getVariant(), k -> new ArrayList<>()).add(row);
                }
            }
        }
        List<Listing> listings = new ArrayList<>();
        List<String> activeInOrder = new ArrayList<>();
        live.entrySet().stream()
                .sorted((a, b) -> Integer.compare(a.getValue().get(0).getSortOrder(), b.getValue().get(0).getSortOrder()))
                .forEach(e -> activeInOrder.add(e.getKey()));

        Optional<String> best = BOOSTING.contains(sku) ? settings.resolveBestValue(sku, activeInOrder) : Optional.empty();

        for (String variant : activeInOrder) {
            listings.add(listing(sku, variant, live.get(variant), true, stored.get(variant), best));
        }
        // Hidden listings: only those an admin turned off here, not tiers a migration retired.
        for (Map.Entry<String, ListingSettings.Stored> e : stored.entrySet()) {
            if (e.getValue().hiddenSince() != null && !live.containsKey(e.getKey())) {
                listings.add(listing(sku, e.getKey(), lastPrices(sku, e.getKey()), false, e.getValue(), best));
            }
        }

        String choice = !BOOSTING.contains(sku) ? null
                : settings.bestValue(sku).map(v -> v.isPresent() ? "CHOSEN" : "NONE").orElse("DEFAULT");
        return new Category(sku.name(), name(sku), listings, choice);
    }

    private Listing listing(Sku sku, String variant, List<RateCardEntity> rows, boolean active,
                            ListingSettings.Stored stored, Optional<String> best) {
        Map<String, Price> prices = new TreeMap<>();
        String label = variant;
        int sort = 0;
        for (RateCardEntity row : rows) {
            prices.put(row.getCurrency().name(), new Price(row.getUnitPriceMinor(),
                    Money.ofMinor(row.getUnitPriceMinor(), row.getCurrency()).format()));
            if (row.getLabel() != null) {
                label = row.getLabel();
            }
            sort = row.getSortOrder();
        }
        Integer rate = null;
        String source = null;
        if (BOOSTING.contains(sku)) {
            rate = settings.successRate(variant, stored);
            source = stored != null && stored.successRateSet() ? "LISTING" : rate != null ? "CONFIGURATION" : null;
        }
        return new Listing(sku.name(), variant, label, sort, active, prices, rate, source,
                active && best.map(variant::equals).orElse(false));
    }

    /** For a hidden listing, the newest price it had in each currency. */
    private List<RateCardEntity> lastPrices(Sku sku, String variant) {
        Map<Currency, RateCardEntity> newest = new LinkedHashMap<>();
        for (RateCardEntity row : rates.findBySeasonAndSkuAndVariantOrderByValidFromDesc(props.season(), sku, variant)) {
            newest.putIfAbsent(row.getCurrency(), row);
        }
        return new ArrayList<>(newest.values());
    }

    public record Change(Map<String, Long> prices, boolean active, Integer successRateBps, boolean bestValue) {
    }

    /**
     * Saves one listing: prices in each currency, on or off, and for boosting its success
     * rate and whether it carries Best Value. One transaction, so a save either lands in
     * full or not at all.
     */
    @Transactional
    public void save(Sku sku, String variant, Change change, Long adminId) {
        requireService(sku);
        List<RateCardEntity> history = rates.findBySeasonAndSkuAndVariantOrderByValidFromDesc(props.season(), sku, variant);
        if (history.isEmpty()) {
            throw new ApiExceptions.NotFoundException("No such listing.");
        }
        Map<Currency, Long> prices = parsePrices(change.prices());
        Instant now = clock.instant();

        if (!change.active()) {
            for (RateCardEntity row : history) {
                if (row.isLive()) {
                    row.close(now);
                    rates.save(row);
                }
            }
            settings.setHidden(sku, variant, true, adminId);
            if (BOOSTING.contains(sku) && settings.bestValue(sku).flatMap(v -> v).map(variant::equals).orElse(false)) {
                // A hidden listing cannot carry the tag; none does until an admin chooses.
                settings.setBestValue(sku, null, adminId);
            }
            log.info("Admin {} hid listing {} {}", adminId, sku, variant);
        } else {
            Map<Currency, RateCardEntity> newest = new LinkedHashMap<>();
            for (RateCardEntity row : history) {
                newest.putIfAbsent(row.getCurrency(), row);
            }
            for (Map.Entry<Currency, RateCardEntity> e : newest.entrySet()) {
                RateCardEntity current = e.getValue();
                long price = prices.getOrDefault(e.getKey(), current.getUnitPriceMinor());
                if (current.isLive() && current.getUnitPriceMinor() == price) {
                    continue;
                }
                if (current.isLive()) {
                    current.close(now);
                    rates.save(current);
                    rates.flush();
                }
                rates.save(copyWithPrice(current, price, adminId));
            }
            // A currency this listing has never been sold in, priced for the first time.
            for (Map.Entry<Currency, Long> e : prices.entrySet()) {
                if (!newest.containsKey(e.getKey())) {
                    RateCardEntity template = history.get(0);
                    rates.save(new RateCardEntity(props.season(), sku, template.getPlatform(), variant, e.getKey(),
                            sku.unit(), e.getValue(), template.getMinQuantity(), template.getMaxQuantity(),
                            template.getStepQuantity(), template.getLabel(), template.getSortOrder(), adminId));
                }
            }
            settings.setHidden(sku, variant, false, adminId);
        }

        if (BOOSTING.contains(sku)) {
            if (change.successRateBps() != null && (change.successRateBps() < 1 || change.successRateBps() > 10_000)) {
                throw new ApiExceptions.BadRequestException("A success rate is between 0.01% and 100%.");
            }
            settings.setSuccessRate(sku, variant, change.successRateBps(), adminId);
            if (change.active()) {
                boolean carries = settings.resolveBestValue(sku, activeVariants(sku)).map(variant::equals).orElse(false);
                if (change.bestValue() && !carries) {
                    settings.setBestValue(sku, variant, adminId);
                } else if (!change.bestValue() && carries) {
                    settings.setBestValue(sku, null, adminId);
                }
            }
        }
        log.info("Admin {} saved listing {} {}", adminId, sku, variant);
    }

    public record NewListing(Sku sku, String name, Map<String, Long> prices, Integer successRateBps) {
    }

    /**
     * Adds a boosting tier. Its code is made from its name and must not have been used
     * before for the service, so an old order's tier can never be confused with a new one.
     *
     * @return the new listing's code
     */
    @Transactional
    public String add(NewListing request, Long adminId) {
        if (!BOOSTING.contains(request.sku())) {
            throw new ApiExceptions.BadRequestException("New listings can be added to Champs or Rivals.");
        }
        String name = request.name() == null ? "" : request.name().trim();
        if (name.length() < 3 || name.length() > 60) {
            throw new ApiExceptions.BadRequestException("Give the listing a name of 3 to 60 characters.");
        }
        String variant = code(name);
        if (variant.isEmpty()) {
            throw new ApiExceptions.BadRequestException("The name needs some letters or numbers.");
        }
        if (rates.existsBySkuAndVariant(request.sku(), variant)) {
            throw new ApiExceptions.ConflictException("listing_exists",
                    "A listing called that exists already, or did once. Choose another name, or turn the old one back on.");
        }
        Map<Currency, Long> prices = parsePrices(request.prices());
        if (prices.isEmpty()) {
            throw new ApiExceptions.BadRequestException("Give it a price in at least one currency.");
        }
        int sort = activeVariantsRows(request.sku()).stream().mapToInt(RateCardEntity::getSortOrder).max().orElse(0) + 1;
        for (Map.Entry<Currency, Long> e : prices.entrySet()) {
            rates.save(new RateCardEntity(props.season(), request.sku(), null, variant, e.getKey(), request.sku().unit(),
                    e.getValue(), null, null, null, name, sort, adminId));
        }
        if (request.successRateBps() != null) {
            if (request.successRateBps() < 1 || request.successRateBps() > 10_000) {
                throw new ApiExceptions.BadRequestException("A success rate is between 0.01% and 100%.");
            }
            settings.setSuccessRate(request.sku(), variant, request.successRateBps(), adminId);
        }
        log.info("Admin {} added listing {} {} ({})", adminId, request.sku(), variant, name);
        return variant;
    }

    /** "16 wins · Elite I+" to "16_WINS_ELITE_I". Letters and digits only, 40 at most. */
    static String code(String name) {
        String code = name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        return code.length() > 40 ? code.substring(0, 40).replaceAll("_+$", "") : code;
    }

    private List<String> activeVariants(Sku sku) {
        return activeVariantsRows(sku).stream()
                .sorted((a, b) -> Integer.compare(a.getSortOrder(), b.getSortOrder()))
                .map(RateCardEntity::getVariant).distinct().toList();
    }

    private List<RateCardEntity> activeVariantsRows(Sku sku) {
        List<RateCardEntity> out = new ArrayList<>();
        for (Currency currency : Currency.values()) {
            for (RateCardEntity row : rates.findLiveForSeason(props.season(), currency)) {
                if (row.getSku() == sku && row.getVariant() != null) {
                    out.add(row);
                }
            }
        }
        return out;
    }

    private RateCardEntity copyWithPrice(RateCardEntity from, long price, Long adminId) {
        return new RateCardEntity(from.getSeason(), from.getSku(), from.getPlatform(), from.getVariant(),
                from.getCurrency(), from.getPriceUnit(), price, from.getMinQuantity(), from.getMaxQuantity(),
                from.getStepQuantity(), from.getLabel(), from.getSortOrder(), adminId);
    }

    private Map<Currency, Long> parsePrices(Map<String, Long> raw) {
        Map<Currency, Long> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        Set<Currency> enabled = EnumSet.copyOf(currencies().isEmpty() ? List.of(Currency.INR) : currencies());
        for (Map.Entry<String, Long> e : raw.entrySet()) {
            Currency currency;
            try {
                currency = Currency.valueOf(e.getKey().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new ApiExceptions.BadRequestException("Unknown currency " + e.getKey() + ".");
            }
            if (!enabled.contains(currency)) {
                throw new ApiExceptions.BadRequestException(currency + " is not a currency the site sells in.");
            }
            Long minor = e.getValue();
            if (minor == null) {
                continue;
            }
            if (minor <= 0 || minor > MAX_PRICE_MINOR) {
                throw new ApiExceptions.BadRequestException("A " + currency + " price must be more than zero and under "
                        + Money.ofMinor(MAX_PRICE_MINOR, currency).format() + ".");
            }
            out.put(currency, minor);
        }
        return out;
    }

    private static void requireService(Sku sku) {
        if (!SERVICES.contains(sku)) {
            throw new ApiExceptions.BadRequestException("Listings covers boosting and coaching. Coins are on Coin rates.");
        }
    }

    static String name(Sku sku) {
        return switch (sku) {
            case BOOST_CHAMPS -> "Champs Wins";
            case BOOST_RIVALS -> "Rivals Divisions";
            case COACHING -> "Coaching";
            default -> sku.displayName();
        };
    }
}
