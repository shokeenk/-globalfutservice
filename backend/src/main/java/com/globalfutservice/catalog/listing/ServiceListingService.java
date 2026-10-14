package com.globalfutservice.catalog.listing;

import com.globalfutservice.catalog.RateCardEntity;
import com.globalfutservice.catalog.RateCardRepository;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.PriceUnit;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.web.ApiExceptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Objectives and Tournaments listings: what the GFS team writes in Service Listings, what
 * the Boosting page and the checkout show, and what an order freezes when it is placed.
 *
 * <p><b>Prices are rate card rows</b> under the listing's variant ({@code LISTING_<id>}),
 * one per currency, versioned as every price is: a change closes the live row and opens
 * another, so an order placed last month can still be explained. A currency left empty is
 * a currency the listing is not sold in. The rows stay live while a listing is a draft, so
 * it can be priced before it goes on sale; whether it is on sale is the listing's own
 * Active switch, and the catalogue and the quote both refuse one that is off.
 *
 * <p><b>Deleting is soft.</b> The listing disappears from the admin and the storefront and
 * its prices close, but the row stays: orders name it, and its history says who did what.
 */
@Service
public class ServiceListingService {

    private static final Logger log = LoggerFactory.getLogger(ServiceListingService.class);

    /** The most hours one order can book unless an admin sets otherwise: a typo stop, not a limit. */
    public static final int DEFAULT_MAX_HOURS = 50;
    static final int MAX_HOURS_CAP = 1000;
    /** Enough for any real price in any currency; stops a slipped zero, not a business. */
    static final long MAX_PRICE_MINOR = 10_000_000L;

    public static final Set<Sku> SKUS = EnumSet.of(Sku.BOOST_OBJECTIVES, Sku.BOOST_TOURNAMENTS);

    /** Where a card image is served. Its bytes never change, so the URL can be cached for ever. */
    public static String imageUrl(Long imageId) {
        return imageId == null ? null : "/api/v1/catalog/listing-images/" + imageId;
    }

    private final ServiceListingRepository listings;
    private final ServiceListingImageRepository images;
    private final ServiceListingChangeRepository changes;
    private final RateCardRepository rates;
    private final OrderRepository orders;
    private final AccountRepository accounts;
    private final AppProperties props;
    private final Clock clock;

    public ServiceListingService(ServiceListingRepository listings, ServiceListingImageRepository images,
                                 ServiceListingChangeRepository changes, RateCardRepository rates,
                                 OrderRepository orders, AccountRepository accounts, AppProperties props,
                                 Clock clock) {
        this.listings = listings;
        this.images = images;
        this.changes = changes;
        this.rates = rates;
        this.orders = orders;
        this.accounts = accounts;
        this.props = props;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ shapes

    /** What the Add / Edit Listing form sends. Prices in minor units; a missing currency is not sold. */
    public record Form(String sku, String title, String subtitle, String overview, String requirements,
                       Integer requiredGames, String estimatedTime, String schedulingNote, String reward,
                       String customerInstructions, String pricingType, Integer maxHours, Boolean showImage,
                       boolean active, Integer displayOrder, Map<String, Long> prices) {
    }

    public record Price(long minor, String formatted) {
    }

    public record Image(long id, String url, int width, int height, int sizeBytes) {
    }

    public record Change(String action, String detail, String by, Instant at) {
    }

    /** One listing as the admin edits it. */
    public record AdminView(long id, String sku, String variant, String title, String subtitle, String overview,
                            String requirements, Integer requiredGames, String estimatedTime,
                            String schedulingNote, String reward, String customerInstructions,
                            String pricingType, int maxHours, boolean showImage, Image image, boolean active,
                            int displayOrder, Map<String, Price> prices, String createdBy, Instant createdAt,
                            String updatedBy, Instant updatedAt, List<Change> changes) {
    }

    /**
     * One listing as the storefront shows it: the card and the checkout. Requirements are
     * the admin's lines, one task each.
     */
    public record PublicView(long id, String title, String subtitle, String imageUrl, Integer imageWidth,
                             Integer imageHeight, boolean showImage, String pricingType, Integer minHours,
                             Integer maxHours, String overview, List<String> requirements,
                             Integer requiredGames, String estimatedTime, String schedulingNote, String reward,
                             String customerInstructions) {
    }

    /** A listing on sale in one currency, with its live row. */
    public record OnSale(ServiceListingEntity listing, RateCardEntity row, PublicView view) {
    }

    // ------------------------------------------------------------------ admin reads

    @Transactional(readOnly = true)
    public List<AdminView> forAdmin(Sku sku) {
        List<AdminView> out = new ArrayList<>();
        for (ServiceListingEntity l : listings.findForAdmin(sku)) {
            out.add(adminView(l, false));
        }
        return out;
    }

    @Transactional(readOnly = true)
    public AdminView one(long id) {
        return adminView(require(id), true);
    }

    /** How many orders name this listing, for the delete confirmation. */
    @Transactional(readOnly = true)
    public long ordersReferencing(long id) {
        ServiceListingEntity l = require(id);
        return orders.countByVariantAndSkuIn(l.variant(), SKUS);
    }

    private AdminView adminView(ServiceListingEntity l, boolean withChanges) {
        Map<String, Price> prices = new TreeMap<>();
        for (RateCardEntity row : liveRows(l.getSku(), l.variant()).values()) {
            prices.put(row.getCurrency().name(), new Price(row.getUnitPriceMinor(),
                    Money.ofMinor(row.getUnitPriceMinor(), row.getCurrency()).format()));
        }
        List<Change> history = !withChanges ? List.of()
                : changes.findTop10ByListingIdOrderByAtDescIdDesc(l.getId()).stream()
                        .map(c -> new Change(c.getAction(), c.getDetail(), who(c.getAdminId()), c.getAt()))
                        .toList();
        return new AdminView(l.getId(), l.getSku().name(), l.variant(), l.getTitle(), l.getSubtitle(),
                l.getOverview(), l.getRequirements(), l.getRequiredGames(), l.getEstimatedTime(),
                l.getSchedulingNote(), l.getReward(), l.getCustomerInstructions(), l.getPricingType().name(),
                l.getMaxHours(), l.isShowImage(), image(l.getImageId()), l.isActive(), l.getDisplayOrder(), prices,
                who(l.getCreatedBy()), l.getCreatedAt(), who(l.getUpdatedBy()), l.getUpdatedAt(), history);
    }

    private Image image(Long imageId) {
        if (imageId == null) {
            return null;
        }
        return images.findInfo(imageId)
                .map(i -> new Image(i.getId(), imageUrl(i.getId()), i.getWidth(), i.getHeight(), i.getSizeBytes()))
                .orElse(null);
    }

    private String who(Long accountId) {
        if (accountId == null) {
            return "System";
        }
        return accounts.findById(accountId)
                .map(a -> a.getDisplayName() != null && !a.getDisplayName().isBlank() ? a.getDisplayName() : a.getEmail())
                .orElse("Account " + accountId);
    }

    // ------------------------------------------------------------------ admin writes

    @Transactional
    public long create(Form form, Long adminId) {
        Sku sku = sku(form.sku());
        Instant now = clock.instant();
        ServiceListingEntity l = new ServiceListingEntity(sku, "-", adminId, now);
        Map<Currency, Long> prices = apply(l, form, true);
        if (form.displayOrder() == null) {
            l.setDisplayOrder(nextDisplayOrder(sku));
        }
        l = listings.saveAndFlush(l);
        writePrices(l, prices, null, adminId);
        record(l, "CREATED", (l.isActive() ? "Active" : "Inactive") + " · " + priceSummary(prices), adminId, now);
        log.info("{} added listing {} ({} {})", adminId == null ? "The system" : "Admin " + adminId, l.getId(), sku,
                l.isActive() ? "active" : "inactive");
        return l.getId();
    }

    @Transactional
    public void update(long id, Form form, Long adminId) {
        ServiceListingEntity l = require(id);
        Instant now = clock.instant();
        Sku before = l.getSku();
        boolean wasActive = l.isActive();
        Map<String, Price> pricesBefore = adminView(l, false).prices();
        Map<Currency, Long> prices = apply(l, form, false);
        l.touched(adminId, now);
        listings.save(l);
        writePrices(l, prices, before, adminId);

        List<String> what = new ArrayList<>();
        if (before != l.getSku()) {
            what.add("moved to " + categoryName(l.getSku()));
        }
        if (wasActive != l.isActive()) {
            record(l, l.isActive() ? "ACTIVATED" : "DEACTIVATED", null, adminId, now);
        }
        Map<String, Price> pricesAfter = adminView(l, false).prices();
        if (!pricesAfter.equals(pricesBefore)) {
            what.add("prices " + priceSummary(prices));
        }
        record(l, "UPDATED", what.isEmpty() ? null : String.join("; ", what), adminId, now);
        log.info("Admin {} saved listing {}", adminId, id);
    }

    /** An inactive copy, "Copy of …", with the same details, artwork and prices. */
    @Transactional
    public long duplicate(long id, Long adminId) {
        ServiceListingEntity from = require(id);
        Instant now = clock.instant();
        String title = ("Copy of " + from.getTitle());
        if (title.length() > 80) {
            title = title.substring(0, 80).trim();
        }
        ServiceListingEntity copy = new ServiceListingEntity(from.getSku(), title, adminId, now);
        copy.setSubtitle(from.getSubtitle());
        copy.setOverview(from.getOverview());
        copy.setRequirements(from.getRequirements());
        copy.setRequiredGames(from.getRequiredGames());
        copy.setEstimatedTime(from.getEstimatedTime());
        copy.setSchedulingNote(from.getSchedulingNote());
        copy.setReward(from.getReward());
        copy.setCustomerInstructions(from.getCustomerInstructions());
        copy.setPricingType(from.getPricingType());
        copy.setMaxHours(from.getMaxHours());
        copy.setShowImage(from.isShowImage());
        copy.setImageId(from.getImageId());
        copy.setActive(false);
        copy.setDisplayOrder(nextDisplayOrder(from.getSku()));
        copy = listings.saveAndFlush(copy);

        Map<Currency, Long> prices = new LinkedHashMap<>();
        liveRows(from.getSku(), from.variant()).forEach((c, row) -> prices.put(c, row.getUnitPriceMinor()));
        writePrices(copy, prices, null, adminId);
        record(copy, "DUPLICATED", "from listing " + from.getId() + " (" + from.getTitle() + ")", adminId, now);
        log.info("Admin {} duplicated listing {} as {}", adminId, id, copy.getId());
        return copy.getId();
    }

    /**
     * Gone from the admin and the storefront, its prices closed, and kept: orders that name
     * it keep their own snapshot, and its history says who deleted it.
     */
    @Transactional
    public void delete(long id, Long adminId) {
        ServiceListingEntity l = require(id);
        Instant now = clock.instant();
        long referencing = orders.countByVariantAndSkuIn(l.variant(), SKUS);
        l.delete(adminId, now);
        listings.save(l);
        closeAll(l.getSku(), l.variant(), now);
        record(l, "DELETED", referencing == 0 ? null : referencing + " order(s) reference it", adminId, now);
        log.info("Admin {} deleted listing {} ({} orders reference it)", adminId, id, referencing);
    }

    @Transactional
    public Image setImage(long id, byte[] bytes, Long adminId) {
        ServiceListingEntity l = require(id);
        PngCheck.Png png;
        try {
            png = PngCheck.check(bytes);
        } catch (PngCheck.NotPng e) {
            throw new ApiExceptions.BadRequestException("invalid_png", e.getMessage());
        }
        Instant now = clock.instant();
        boolean replacing = l.getImageId() != null;
        ServiceListingImageEntity stored = images.saveAndFlush(
                new ServiceListingImageEntity(l.getId(), bytes, png.width(), png.height(), adminId, now));
        l.setImageId(stored.getId());
        l.touched(adminId, now);
        listings.save(l);
        record(l, replacing ? "IMAGE_REPLACED" : "IMAGE_UPLOADED",
                png.width() + " × " + png.height() + " px, " + kb(bytes.length), adminId, now);
        return new Image(stored.getId(), imageUrl(stored.getId()), png.width(), png.height(), bytes.length);
    }

    @Transactional
    public void removeImage(long id, Long adminId) {
        ServiceListingEntity l = require(id);
        if (l.getImageId() == null) {
            return;
        }
        Instant now = clock.instant();
        l.setImageId(null);
        l.touched(adminId, now);
        listings.save(l);
        record(l, "IMAGE_REMOVED", null, adminId, now);
    }

    // ------------------------------------------------------------------ the storefront

    /** Card artwork by id, for anyone: what the storefront and an order's snapshot show. */
    @Transactional(readOnly = true)
    public Optional<ServiceListingImageEntity> imageBytes(long imageId) {
        return images.findById(imageId);
    }

    /** The listings on sale in a currency, each with its live price, in display order. */
    @Transactional(readOnly = true)
    public List<OnSale> onSale(Currency currency) {
        List<OnSale> out = new ArrayList<>();
        for (ServiceListingEntity l : listings.findOnSale(SKUS)) {
            rates.findLive(props.season(), l.getSku(), null, l.variant(), currency).ifPresent(row ->
                    out.add(new OnSale(l, row, publicView(l))));
        }
        return out;
    }

    /**
     * What a quote prices a listing from: its live price in the currency, its current
     * title, and for a listing sold by the hour, whole hours from one to its cap. Refused
     * when the listing is not on sale -- a draft, a deleted one, or none at all.
     */
    @Transactional(readOnly = true)
    public RateCard rateCard(Sku sku, String variant, Currency currency) {
        ServiceListingEntity l = onSaleListing(sku, variant);
        RateCardEntity row = rates.findLive(props.season(), sku, null, variant, currency)
                .orElseThrow(() -> new ApiExceptions.NotFoundException("That option is not available right now."));
        boolean hourly = l.getPricingType() == ServiceListingEntity.PricingType.PER_HOUR;
        return new RateCard(props.season(), sku, null, variant, l.getTitle(),
                Money.ofMinor(row.getUnitPriceMinor(), currency),
                hourly ? BigDecimal.ONE : null,
                hourly ? BigDecimal.valueOf(l.getMaxHours()) : null,
                hourly ? BigDecimal.ONE : null,
                null,
                hourly ? PriceUnit.PER_HOUR : PriceUnit.FLAT);
    }

    /**
     * The listing's price, per hour or fixed, on sale at an instant: what a quote minted then
     * was priced from, however the listing has been repriced since.
     */
    @Transactional(readOnly = true)
    public Optional<Long> unitPriceAt(Sku sku, String variant, Currency currency, Instant at) {
        return rates.findOnSaleAt(props.season(), sku, currency, at).stream()
                .filter(row -> variant.equals(row.getVariant()))
                .map(RateCardEntity::getUnitPriceMinor)
                .findFirst();
    }

    /** The listing an order is being placed for, on sale. */
    @Transactional(readOnly = true)
    public ServiceListingEntity onSaleListing(Sku sku, String variant) {
        Long id = ServiceListingEntity.idOf(variant);
        return Optional.ofNullable(id).flatMap(listings::findById)
                .filter(l -> l.getSku() == sku && l.onSale())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("That option is not available right now."));
    }

    public PublicView publicView(ServiceListingEntity l) {
        Image image = image(l.getImageId());
        boolean hourly = l.getPricingType() == ServiceListingEntity.PricingType.PER_HOUR;
        return new PublicView(l.getId(), l.getTitle(), l.getSubtitle(),
                image == null ? null : image.url(),
                image == null ? null : image.width(),
                image == null ? null : image.height(),
                l.isShowImage(), l.getPricingType().name(),
                hourly ? 1 : null, hourly ? l.getMaxHours() : null,
                l.getOverview(), lines(l.getRequirements()), l.getRequiredGames(), l.getEstimatedTime(),
                l.getSchedulingNote(), l.getReward(), l.getCustomerInstructions());
    }

    /** The admin's lines, one task each: blank lines dropped, a typed bullet taken off. */
    public static List<String> lines(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("\\R"))
                .map(line -> line.replaceFirst("^\\s*[•\\-*–·]\\s*", "").trim())
                .filter(line -> !line.isEmpty())
                .toList();
    }

    public static String categoryName(Sku sku) {
        return sku == Sku.BOOST_TOURNAMENTS ? "Tournaments" : "Objectives";
    }

    // ------------------------------------------------------------------ internals

    private ServiceListingEntity require(long id) {
        return listings.findById(id).filter(l -> !l.deleted())
                .orElseThrow(() -> new ApiExceptions.NotFoundException("No such listing."));
    }

    /** Copies the form onto the listing, checking every field; returns the prices asked for. */
    private Map<Currency, Long> apply(ServiceListingEntity l, Form form, boolean creating) {
        l.setSku(sku(form.sku()));
        String title = text(form.title(), 80, "The title");
        if (title == null) {
            throw new ApiExceptions.BadRequestException("Give the listing a title.");
        }
        l.setTitle(title);
        l.setSubtitle(text(form.subtitle(), 120, "The subtitle"));
        l.setOverview(text(form.overview(), 1000, "The overview"));
        l.setRequirements(text(form.requirements(), 4000, "The listing details"));
        if (lines(l.getRequirements()).size() > 40) {
            throw new ApiExceptions.BadRequestException("Keep the listing details to 40 lines or fewer.");
        }
        if (form.requiredGames() != null && (form.requiredGames() < 1 || form.requiredGames() > 1000)) {
            throw new ApiExceptions.BadRequestException("Required games is a whole number from 1 to 1,000, or empty.");
        }
        l.setRequiredGames(form.requiredGames());
        l.setEstimatedTime(text(form.estimatedTime(), 60, "The estimated completion time"));
        l.setSchedulingNote(text(form.schedulingNote(), 300, "The scheduling note"));
        l.setReward(text(form.reward(), 120, "The reward"));
        l.setCustomerInstructions(text(form.customerInstructions(), 1000, "The customer instructions"));

        ServiceListingEntity.PricingType type;
        try {
            type = form.pricingType() == null || form.pricingType().isBlank() ? ServiceListingEntity.PricingType.FIXED
                    : ServiceListingEntity.PricingType.valueOf(form.pricingType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException("Choose Fixed price or Per hour.");
        }
        l.setPricingType(type);
        int maxHours = form.maxHours() == null ? DEFAULT_MAX_HOURS : form.maxHours();
        if (maxHours < 1 || maxHours > MAX_HOURS_CAP) {
            throw new ApiExceptions.BadRequestException("The most hours per order is a whole number from 1 to "
                    + MAX_HOURS_CAP + ".");
        }
        l.setMaxHours(maxHours);
        l.setShowImage(form.showImage() == null || form.showImage());
        if (form.displayOrder() != null) {
            if (form.displayOrder() < 0 || form.displayOrder() > 9999) {
                throw new ApiExceptions.BadRequestException("Display order is a whole number from 0 to 9,999.");
            }
            l.setDisplayOrder(form.displayOrder());
        }
        Map<Currency, Long> prices = parsePrices(form.prices());
        if (form.active() && prices.isEmpty()) {
            throw new ApiExceptions.BadRequestException(
                    "Give it a price in at least one currency before making it active.");
        }
        l.setActive(form.active());
        return prices;
    }

    private static Sku sku(String raw) {
        try {
            Sku sku = Sku.valueOf(raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT));
            if (SKUS.contains(sku)) {
                return sku;
            }
        } catch (IllegalArgumentException ignored) {
            // falls through
        }
        throw new ApiExceptions.BadRequestException("Choose Objectives or Tournaments.");
    }

    private static String text(String value, int max, String name) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new ApiExceptions.BadRequestException(name + " can be at most " + max + " characters.");
        }
        return trimmed;
    }

    private Map<Currency, Long> parsePrices(Map<String, Long> raw) {
        Map<Currency, Long> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        Set<Currency> enabled = EnumSet.noneOf(Currency.class);
        for (String code : props.pricing().enabledCurrencies()) {
            try {
                enabled.add(Currency.valueOf(code.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // An unknown code in configuration is not a listing's problem.
            }
        }
        if (enabled.isEmpty()) {
            enabled.add(Currency.INR);
        }
        for (Map.Entry<String, Long> e : raw.entrySet()) {
            Currency currency;
            try {
                currency = Currency.valueOf(e.getKey().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new ApiExceptions.BadRequestException("Unknown currency " + e.getKey() + ".");
            }
            Long minor = e.getValue();
            if (minor == null) {
                continue;
            }
            if (!enabled.contains(currency)) {
                throw new ApiExceptions.BadRequestException(currency + " is not a currency the site sells in.");
            }
            if (minor <= 0 || minor > MAX_PRICE_MINOR) {
                throw new ApiExceptions.BadRequestException("A " + currency + " price must be more than zero and under "
                        + Money.ofMinor(MAX_PRICE_MINOR, currency).format() + ".");
            }
            out.put(currency, minor);
        }
        return out;
    }

    /**
     * Makes the live rows say what was asked: a changed price closes the old row and opens a
     * new one, a currency left empty closes its row, and an unchanged price is left alone.
     * A listing moved to the other category has its rows closed there and opened here.
     */
    private void writePrices(ServiceListingEntity l, Map<Currency, Long> wanted, Sku previousSku, Long adminId) {
        Instant now = clock.instant();
        String variant = l.variant();
        if (previousSku != null && previousSku != l.getSku()) {
            closeAll(previousSku, variant, now);
        }
        boolean hourly = l.getPricingType() == ServiceListingEntity.PricingType.PER_HOUR;
        PriceUnit unit = hourly ? PriceUnit.PER_HOUR : PriceUnit.FLAT;
        BigDecimal max = hourly ? BigDecimal.valueOf(l.getMaxHours()) : null;
        Map<Currency, RateCardEntity> live = liveRows(l.getSku(), variant);
        for (Currency currency : Currency.values()) {
            Long price = wanted.get(currency);
            RateCardEntity row = live.get(currency);
            if (price == null) {
                if (row != null) {
                    row.close(now);
                    rates.save(row);
                }
                continue;
            }
            if (row != null && row.getUnitPriceMinor() == price && row.getPriceUnit() == unit
                    && sameNumber(row.getMaxQuantity(), max)) {
                continue;
            }
            if (row != null) {
                row.close(now);
                rates.save(row);
                rates.flush();
            }
            rates.save(new RateCardEntity(props.season(), l.getSku(), null, variant, currency, unit, price,
                    hourly ? BigDecimal.ONE : null, max, hourly ? BigDecimal.ONE : null,
                    l.getTitle(), l.getDisplayOrder(), adminId).startingAt(now));
        }
        rates.flush();
    }

    private static boolean sameNumber(BigDecimal a, BigDecimal b) {
        return a == null ? b == null : b != null && a.compareTo(b) == 0;
    }

    private void closeAll(Sku sku, String variant, Instant now) {
        for (RateCardEntity row : liveRows(sku, variant).values()) {
            row.close(now);
            rates.save(row);
        }
        rates.flush();
    }

    private Map<Currency, RateCardEntity> liveRows(Sku sku, String variant) {
        Map<Currency, RateCardEntity> out = new LinkedHashMap<>();
        for (RateCardEntity row : rates.findBySeasonAndSkuAndVariantOrderByValidFromDesc(props.season(), sku, variant)) {
            if (row.isLive()) {
                out.putIfAbsent(row.getCurrency(), row);
            }
        }
        return out;
    }

    private int nextDisplayOrder(Sku sku) {
        return listings.findForAdmin(sku).stream().mapToInt(ServiceListingEntity::getDisplayOrder).max().orElse(0) + 1;
    }

    private void record(ServiceListingEntity l, String action, String detail, Long adminId, Instant at) {
        changes.save(new ServiceListingChangeEntity(l.getId(), action, detail, adminId, at));
    }

    private static String priceSummary(Map<Currency, Long> prices) {
        if (prices.isEmpty()) {
            return "no prices";
        }
        List<String> parts = new ArrayList<>();
        prices.forEach((c, minor) -> parts.add(Money.ofMinor(minor, c).format()));
        return String.join(", ", parts);
    }

    private static String kb(int bytes) {
        return Math.max(1, Math.round(bytes / 1024.0)) + " KB";
    }

    /** For the draft seeds: created once, by the system, and marked so it is never created again. */
    @Transactional
    public long createSeed(String seedKey, Form form) {
        long id = create(form, null);
        ServiceListingEntity l = require(id);
        l.setSeedKey(seedKey);
        listings.save(l);
        return id;
    }

    /** Whether the seed was created before, or a listing of that title exists already. */
    @Transactional(readOnly = true)
    public boolean seedExists(String seedKey, String title) {
        return listings.findBySeedKey(seedKey).isPresent() || listings.existsByTitleIgnoreCase(title);
    }

}
