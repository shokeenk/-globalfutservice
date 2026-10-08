package com.globalfutservice.admin;

import com.globalfutservice.catalog.CoinPriceStore;
import com.globalfutservice.catalog.CoinPricingService;
import com.globalfutservice.catalog.RateCardEntity;
import com.globalfutservice.catalog.RateCardRepository;
import com.globalfutservice.domain.catalog.CoinMarket;
import com.globalfutservice.domain.catalog.CoinPriceTable;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.CoinBaseRate;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.PriceUnit;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Price administration.
 *
 * <p>Changing a price is an insert, never an update. The current row is closed with a
 * {@code validTo} timestamp and a new one opened — so the history of every price is
 * preserved, an order placed last month can still be explained, and a mistaken change can
 * be undone by making another one rather than by restoring a backup.
 *
 * <p>Restricted to ADMIN rather than OPERATOR: an operator fulfils orders, but changing
 * what the business charges is a different kind of authority.
 */
@RestController
@RequestMapping("/api/v1/admin/rates")
@Tag(name = "Admin — pricing", description = "Rate card administration")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRateCardController {

    private static final Logger log = LoggerFactory.getLogger(AdminRateCardController.class);

    private final RateCardRepository rates;
    private final AppProperties props;
    private final CoinPricingService coinPricing;

    public AdminRateCardController(RateCardRepository rates, AppProperties props, CoinPricingService coinPricing) {
        this.rates = rates;
        this.props = props;
        this.coinPricing = coinPricing;
    }

    public record RateRowDto(
            Long id, String season, String sku, String platform, String variant,
            String currency, String priceUnit, long unitPriceMinor, String unitPriceFormatted,
            BigDecimal minQuantity, BigDecimal maxQuantity, BigDecimal stepQuantity,
            String label, Instant validFrom, Instant validTo, boolean live) {
    }

    public record UpdateRateRequest(
            @NotBlank String sku,
            String platform,
            String variant,
            @NotBlank String currency,
            @Positive(message = "A price must be greater than zero")
            long unitPriceMinor,
            BigDecimal minQuantity,
            BigDecimal maxQuantity,
            BigDecimal stepQuantity,
            String label,
            Integer sortOrder) {
    }

    @GetMapping
    @Operation(summary = "Live rates for a currency")
    public ResponseEntity<List<RateRowDto>> live(@RequestParam(defaultValue = "INR") String currency) {
        Currency parsed = parse(Currency.class, currency);
        return ResponseEntity.ok(rates.findLiveForSeason(props.season(), parsed).stream()
                .map(AdminRateCardController::toDto).toList());
    }

    @GetMapping("/history")
    @Operation(summary = "Every price this SKU has ever had")
    public ResponseEntity<List<RateRowDto>> history(@RequestParam String sku,
                                                    @RequestParam(defaultValue = "INR") String currency) {
        return ResponseEntity.ok(rates.findHistory(props.season(),
                        parse(Sku.class, sku), parse(Currency.class, currency)).stream()
                .map(AdminRateCardController::toDto).toList());
    }

    @PostMapping
    @Operation(summary = "Change a price",
            description = "Closes the current row and opens a new one. Nothing is overwritten.")
    @Transactional
    public ResponseEntity<RateRowDto> update(@Valid @RequestBody UpdateRateRequest request,
                                             @CurrentAccount AccountPrincipal admin) {
        Sku sku = parse(Sku.class, request.sku());
        if (sku == Sku.TRADING_SERVICE) {
            // A coin row here would be read by nothing: coins are priced per structure.
            throw new ApiExceptions.BadRequestException("coin_prices_elsewhere",
                    "Coin prices are set per structure, on the coin pricing page.");
        }
        Currency currency = parse(Currency.class, request.currency());
        Platform platform = request.platform() == null || request.platform().isBlank()
                ? null : parse(Platform.class, request.platform());

        RateCardEntity current = rates.findLive(
                props.season(), sku, platform, request.variant(), currency).orElse(null);

        Instant now = Instant.now();
        if (current != null) {
            if (current.getUnitPriceMinor() == request.unitPriceMinor()) {
                // No-op writes would litter the history and make it useless.
                return ResponseEntity.ok(toDto(current));
            }
            current.close(now);
            rates.save(current);
            rates.flush();
        }

        RateCardEntity replacement = new RateCardEntity(
                props.season(), sku, platform, request.variant(), currency,
                sku.unit(),
                request.unitPriceMinor(),
                orElse(request.minQuantity(), current == null ? null : current.getMinQuantity()),
                orElse(request.maxQuantity(), current == null ? null : current.getMaxQuantity()),
                orElse(request.stepQuantity(), current == null ? null : current.getStepQuantity()),
                request.label() != null ? request.label() : (current == null ? null : current.getLabel()),
                request.sortOrder() != null ? request.sortOrder()
                        : (current == null ? 0 : current.getSortOrder()),
                admin.id());

        RateCardEntity saved = rates.save(replacement);
        log.info("Admin {} changed {} {} {} to {}", admin.publicId(), sku, platform,
                request.variant(), Money.ofMinor(request.unitPriceMinor(), currency).format());
        return ResponseEntity.ok(toDto(saved));
    }

    // =========================================================================
    //  Coin base rates -- the Coin rates page as it stands.
    //
    //  Coin prices now live in two structures, PC and PlayStation + Xbox, each
    //  with its own slider and optional volume brackets (CoinPricingService).
    //  Until the admin page for those replaces this one, these two endpoints keep
    //  the old page working on top of them: it reads PC's base price per 100,000,
    //  and a save sets that base price in both structures, leaving everything else
    //  in them -- range, quick picks, brackets -- as it was. One price for every
    //  platform, which is what this page always meant.
    // =========================================================================

    public record CoinRateDto(
            String currency,
            String symbol,
            /** What the rate card stores, for anyone reconciling against the table. */
            long perMillionMinor,
            /** What the owner sets: the price of 100,000 coins, in major units. */
            BigDecimal per100k,
            /** What one slider step costs, derived — may carry a fraction of a cent. */
            BigDecimal per10k,
            /**
             * Whether a 10,000-coin step lands on a whole minor unit.
             *
             * <p>False means the price is still exact — the engine rounds once, at the
             * total, and never accumulates — but consecutive steps on screen differ by
             * one minor unit more or less than the others. The admin screen says so
             * rather than leaving the owner to notice it in a customer's receipt.
             */
            boolean stepIsWholeMinorUnit,
            Instant validFrom) {
    }

    public record CoinRateInput(@NotBlank String currency, BigDecimal per100k) {
    }

    public record UpdateCoinRatesRequest(List<CoinRateInput> rates) {
    }

    @GetMapping("/coin-rates")
    @Operation(summary = "The coin base price per 100,000, per currency",
            description = "One number per currency: the PC structure's base price. Not converted from any other currency.")
    public ResponseEntity<List<CoinRateDto>> coinRates() {
        return ResponseEntity.ok(coinRateDtos());
    }

    private List<CoinRateDto> coinRateDtos() {
        List<CoinRateDto> out = new ArrayList<>();
        CoinPriceStore.Version pc = coinPricing.live().get(CoinMarket.PC);
        if (pc == null) {
            return out;
        }
        for (String code : props.pricing().enabledCurrencies()) {
            Currency currency = parse(Currency.class, code);
            pc.table().base(currency).ifPresent(perMillionMinor ->
                    out.add(toCoinDto(currency, perMillionMinor, pc.validFrom())));
        }
        return out;
    }

    @PostMapping("/coin-rates")
    @Operation(summary = "Set the coin base price for one or more currencies",
            description = "Writes every platform for each currency. Closes the old rows, "
                    + "opens new ones — nothing is overwritten.")
    @Transactional
    public ResponseEntity<List<CoinRateDto>> updateCoinRates(
            @Valid @RequestBody UpdateCoinRatesRequest request,
            @CurrentAccount AccountPrincipal admin) {

        if (request.rates() == null || request.rates().isEmpty()) {
            throw new ApiExceptions.BadRequestException("No rates were supplied.");
        }

        java.util.Map<Currency, Long> bases = new java.util.EnumMap<>(Currency.class);
        for (CoinRateInput input : request.rates()) {
            Currency currency = parse(Currency.class, input.currency());
            bases.put(currency, toPerMillionMinor(currency, input.per100k()));
        }

        java.util.Map<CoinMarket, CoinPriceStore.Version> live = coinPricing.live();
        for (CoinMarket market : CoinMarket.values()) {
            CoinPriceStore.Version current = live.get(market);
            if (current == null) {
                throw new ApiExceptions.BadRequestException("There are no " + market.displayName()
                        + " coin prices to change for " + props.season() + ".");
            }
            coinPricing.save(withBases(current.table(), bases), admin.id(), admin.publicId());
        }
        return ResponseEntity.ok(coinRateDtos());
    }

    /** {@code table} with these currencies' base prices, and nothing else changed. */
    private static CoinPriceTable withBases(CoinPriceTable table, java.util.Map<Currency, Long> bases) {
        java.util.Map<Currency, List<CoinPriceTable.Bracket>> rates = new java.util.EnumMap<>(Currency.class);
        rates.putAll(table.rates());
        bases.forEach((currency, perMillionMinor) -> {
            List<CoinPriceTable.Bracket> next = new ArrayList<>();
            next.add(new CoinPriceTable.Bracket(0, perMillionMinor));
            next.addAll(table.brackets(currency));
            rates.put(currency, next);
        });
        return new CoinPriceTable(null, table.season(), table.market(), table.minK(), table.maxK(), table.stepK(),
                table.quickPicksK(), rates);
    }

    /**
     * The owner's number as the column stores it, with a domain error turned into a 400.
     *
     * <p>The arithmetic and the validation both live in {@link CoinBaseRate}; this only
     * decides what a bad price looks like over HTTP.
     */
    private static long toPerMillionMinor(Currency currency, BigDecimal per100k) {
        try {
            return CoinBaseRate.toPerMillionMinor(currency, per100k);
        } catch (IllegalArgumentException e) {
            throw new ApiExceptions.BadRequestException(e.getMessage());
        }
    }

    private static CoinRateDto toCoinDto(Currency currency, long perMillionMinor, Instant validFrom) {
        return new CoinRateDto(
                currency.name(),
                currency.symbol(),
                perMillionMinor,
                CoinBaseRate.per100k(currency, perMillionMinor),
                CoinBaseRate.per10k(currency, perMillionMinor),
                CoinBaseRate.stepIsWholeMinorUnit(perMillionMinor),
                validFrom);
    }

    private static BigDecimal orElse(BigDecimal value, BigDecimal fallback) {
        return value != null ? value : fallback;
    }

    private static RateRowDto toDto(RateCardEntity r) {
        return new RateRowDto(
                r.getId(), r.getSeason(), r.getSku().name(),
                r.getPlatform() == null ? null : r.getPlatform().name(),
                r.getVariant(), r.getCurrency().name(),
                r.getPriceUnit() == null ? PriceUnit.FLAT.name() : r.getPriceUnit().name(),
                r.getUnitPriceMinor(),
                Money.ofMinor(r.getUnitPriceMinor(), r.getCurrency()).format(),
                r.getMinQuantity(), r.getMaxQuantity(), r.getStepQuantity(),
                r.getLabel(), r.getValidFrom(), r.getValidTo(), r.isLive());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw) {
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiExceptions.BadRequestException("Unknown value: " + raw);
        }
    }
}
