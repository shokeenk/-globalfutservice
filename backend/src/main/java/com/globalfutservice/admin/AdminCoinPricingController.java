package com.globalfutservice.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.globalfutservice.catalog.CoinPriceStore;
import com.globalfutservice.catalog.CoinPricingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.CoinBaseRate;
import com.globalfutservice.domain.catalog.CoinMarket;
import com.globalfutservice.domain.catalog.CoinPriceTable;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.pricing.CustomerPricingContext;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.security.CurrentAccount;
import com.globalfutservice.web.ApiExceptions;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Coin pricing, per structure: PC, and PlayStation + Xbox.
 *
 * <p>Each structure has its slider -- minimum, maximum and step, and the quick-pick
 * amounts -- and, per currency, a base price with optional volume brackets. Prices are
 * typed per 100,000 coins, as the business quotes them; every one comes back with its
 * per-million equivalent beside it, which is what the engine multiplies and what the owner
 * checks a price list against.
 *
 * <p>A save makes a new version and closes the old one; nothing is overwritten. Errors stop
 * a save. Warnings never do: a bracket that makes more coins cost less, or a minimum below
 * FUT Transfer's per-transfer minimum, is the business's call, said out loud first.
 *
 * <p>ADMIN only, like every price: an operator fulfils orders, an admin sets what they cost.
 */
@RestController
@RequestMapping("/api/v1/admin/coin-pricing")
@Tag(name = "Admin — pricing", description = "Rate card administration")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCoinPricingController {

    private static final int HISTORY = 20;

    private final CoinPricingService pricing;
    private final PricingEngine engine;
    private final AppProperties props;

    public AdminCoinPricingController(CoinPricingService pricing, PricingEngine engine, AppProperties props) {
        this.pricing = pricing;
        this.engine = engine;
        this.props = props;
    }

    // ------------------------------------------------------------------ shapes

    /** The rules a structure is checked against, so the page can say them before a save does. */
    public record Limits(int smallestStepK, int maxCapK, int vendorMinTransferK, List<Integer> defaultQuickPicksK,
                         int maxQuickPicks, int maxBrackets) {
    }

    /** One price, as typed (per 100K) and as the engine uses it (per 1M). */
    public record Rate(int fromK, BigDecimal per100k, String per100kFormatted, long perMillionMinor,
                       String perMillionFormatted) {
    }

    public record CurrencyRates(String currency, String symbol, Rate base, List<Rate> brackets) {
    }

    public record Structure(String market, String label, List<String> platforms, Long version, Instant validFrom,
                            Instant validTo, String setBy, int minK, int maxK, int stepK, List<Integer> quickPicksK,
                            List<CurrencyRates> rates, List<String> warnings) {
    }

    public record Overview(String season, List<String> currencies, Limits limits, List<Structure> structures) {
    }

    public record BracketInput(Integer fromK, BigDecimal per100k) {
    }

    public record RateInput(String currency, BigDecimal per100k, List<BracketInput> brackets) {
    }

    /**
     * A structure as the admin page sends it. {@code quickPicksK} left out means the default
     * set, within the range; an empty list means no quick picks. {@code previewK} only
     * matters to a preview: the amounts to price, or a sensible set when left out.
     */
    public record Draft(Integer minK, Integer maxK, Integer stepK, List<Integer> quickPicksK, List<RateInput> rates,
                        List<Integer> previewK) {
    }

    /** What an amount costs: the coins alone, and what a guest pays with no discount, fee included. */
    public record PreviewRow(int amountK, String currency, BigDecimal per100k, String per100kFormatted,
                             String perMillionFormatted, long coinPriceMinor, String coinPriceFormatted,
                             long guestTotalMinor, String guestTotalFormatted) {
    }

    public record Preview(List<String> errors, List<String> warnings, List<PreviewRow> rows) {
    }

    // ------------------------------------------------------------------ reads

    @GetMapping
    @Operation(summary = "Both coin price structures, live, with their warnings")
    public ResponseEntity<Overview> overview() {
        Map<CoinMarket, CoinPriceStore.Version> live = pricing.live();
        List<Structure> structures = new ArrayList<>();
        for (CoinMarket market : CoinMarket.values()) {
            CoinPriceStore.Version v = live.get(market);
            if (v != null) {
                structures.add(view(v));
            }
        }
        return noStore(new Overview(props.season(), pricing.requiredCurrencies().stream().map(Enum::name).toList(),
                limits(), structures));
    }

    @GetMapping("/{market}/history")
    @Operation(summary = "Every version of one structure this season, newest first")
    public ResponseEntity<List<Structure>> history(@PathVariable String market) {
        return noStore(pricing.history(market(market), HISTORY).stream().map(this::view).toList());
    }

    // ------------------------------------------------------------------ preview and save

    @PostMapping("/{market}/preview")
    @Operation(summary = "Check a structure and price some amounts with it, without saving anything")
    public ResponseEntity<Preview> preview(@PathVariable String market, @RequestBody Draft draft) {
        Converted c = convert(market(market), draft);
        if (!c.errors().isEmpty()) {
            // Not yet a structure: checking it would only add errors that follow from these.
            return noStore(new Preview(c.errors(), List.of(), List.of()));
        }
        CoinPriceTable.Check check = pricing.check(c.table());
        List<PreviewRow> rows = check.ok() ? rows(c.table(), draft.previewK()) : List.of();
        return noStore(new Preview(check.errors(), check.warnings(), rows));
    }

    @PutMapping("/{market}")
    @Operation(summary = "Set one structure's slider and prices",
            description = "Closes the live version and opens a new one. Refused with every error when it cannot be "
                    + "sold; warnings are returned, never refused.")
    public ResponseEntity<Structure> save(@PathVariable String market, @RequestBody Draft draft,
                                          @CurrentAccount AccountPrincipal admin) {
        Converted c = convert(market(market), draft);
        if (!c.errors().isEmpty()) {
            throw new ApiExceptions.BadRequestException("invalid_coin_pricing", String.join("\n", c.errors()));
        }
        return noStore(view(pricing.save(c.table(), admin.id(), admin.publicId())));
    }

    // ------------------------------------------------------------------ helpers

    private record Converted(CoinPriceTable table, List<String> errors) {
    }

    /** The page's numbers as a structure, and anything that could not be read as one. */
    private Converted convert(CoinMarket market, Draft draft) {
        List<String> errors = new ArrayList<>();
        int minK = required(draft.minK(), "Set the minimum.", errors);
        int maxK = required(draft.maxK(), "Set the maximum.", errors);
        int stepK = required(draft.stepK(), "Set the step.", errors);

        List<Integer> picks;
        if (draft.quickPicksK() == null) {
            picks = CoinPriceTable.DEFAULT_QUICK_PICKS_K.stream()
                    .filter(k -> stepK > 0 && k >= minK && k <= maxK && (k - minK) % stepK == 0).toList();
        } else if (draft.quickPicksK().stream().anyMatch(java.util.Objects::isNull)) {
            errors.add("A quick pick is empty.");
            picks = draft.quickPicksK().stream().filter(java.util.Objects::nonNull).toList();
        } else {
            picks = draft.quickPicksK();
        }

        Map<Currency, List<CoinPriceTable.Bracket>> rates = new EnumMap<>(Currency.class);
        Set<Currency> seen = new HashSet<>();
        for (RateInput input : draft.rates() == null ? List.<RateInput>of() : draft.rates()) {
            Currency currency;
            try {
                currency = Currency.valueOf(String.valueOf(input.currency()).trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                errors.add("Unknown currency: " + input.currency() + ".");
                continue;
            }
            if (!seen.add(currency)) {
                errors.add(currency + " is listed twice.");
                continue;
            }
            List<CoinPriceTable.Bracket> brackets = new ArrayList<>();
            perMillion(currency, input.per100k(), currency + " base price", errors)
                    .ifPresent(rate -> brackets.add(new CoinPriceTable.Bracket(0, rate)));
            for (BracketInput b : input.brackets() == null ? List.<BracketInput>of() : input.brackets()) {
                if (b.fromK() == null) {
                    errors.add(currency + ": a bracket has no starting amount.");
                    continue;
                }
                perMillion(currency, b.per100k(), currency + " price from " + CoinPriceTable.describeK(b.fromK()), errors)
                        .ifPresent(rate -> brackets.add(new CoinPriceTable.Bracket(b.fromK(), rate)));
            }
            if (!brackets.isEmpty()) {
                rates.put(currency, brackets);
            }
        }
        return new Converted(new CoinPriceTable(null, props.season(), market, minK, maxK, stepK, picks, rates),
                errors);
    }

    private static int required(Integer value, String message, List<String> errors) {
        if (value == null) {
            errors.add(message);
            return 0;
        }
        return value;
    }

    private static java.util.OptionalLong perMillion(Currency currency, BigDecimal per100k, String what,
                                                     List<String> errors) {
        if (per100k == null) {
            errors.add("Set the " + what + ".");
            return java.util.OptionalLong.empty();
        }
        try {
            return java.util.OptionalLong.of(CoinBaseRate.toPerMillionMinor(currency, per100k));
        } catch (IllegalArgumentException e) {
            errors.add(what + ": " + e.getMessage());
            return java.util.OptionalLong.empty();
        }
    }

    /**
     * The amounts a preview prices: the ones asked for, else the minimum, the quick picks,
     * each bracket's first amount and the step below it, and the maximum.
     */
    private List<PreviewRow> rows(CoinPriceTable table, List<Integer> asked) {
        Set<Integer> amounts = new TreeSet<>();
        if (asked != null && !asked.isEmpty()) {
            asked.stream().filter(java.util.Objects::nonNull).limit(50).forEach(amounts::add);
        } else {
            amounts.add(table.minK());
            amounts.addAll(table.quickPicksK());
            for (List<CoinPriceTable.Bracket> brackets : table.rates().values()) {
                for (CoinPriceTable.Bracket b : brackets) {
                    if (b.fromK() > 0) {
                        amounts.add(b.fromK());
                        amounts.add(b.fromK() - table.stepK());
                    }
                }
            }
            amounts.add(table.maxK());
        }
        Platform platform = table.market().platforms().get(0);
        CustomerPricingContext guest = CustomerPricingContext.guest();
        List<PreviewRow> rows = new ArrayList<>();
        for (Currency currency : table.rates().keySet()) {
            for (int k : amounts) {
                if (k < table.minK() || k > table.maxK() || (k - table.minK()) % table.stepK() != 0) {
                    continue;
                }
                long rate = table.perMillionMinor(currency, k).orElseThrow();
                RateCard card = table.rateCard(platform, currency, CoinPriceTable.millions(k));
                var total = engine.quote(card, CoinPriceTable.millions(k), guest).total();
                var coins = table.coinPrice(currency, k);
                rows.add(new PreviewRow(k, currency.name(), CoinBaseRate.per100k(currency, rate),
                        CoinPriceTable.per100k(currency, rate), CoinPriceTable.perMillion(currency, rate),
                        coins.minor(), coins.format(), total.minor(), total.format()));
            }
        }
        return rows;
    }

    private Structure view(CoinPriceStore.Version v) {
        CoinPriceTable t = v.table();
        Set<Currency> currencies = new TreeSet<>(pricing.requiredCurrencies());
        currencies.addAll(t.rates().keySet());
        List<CurrencyRates> rates = new ArrayList<>();
        for (Currency currency : currencies) {
            Rate base = t.base(currency).isPresent() ? rate(currency, 0, t.base(currency).getAsLong()) : null;
            rates.add(new CurrencyRates(currency.name(), currency.symbol(), base,
                    t.brackets(currency).stream().map(b -> rate(currency, b.fromK(), b.perMillionMinor())).toList()));
        }
        return new Structure(t.market().name(), t.market().displayName(),
                t.market().platforms().stream().map(Enum::name).toList(), t.version(), v.validFrom(), v.validTo(),
                v.createdBy(), t.minK(), t.maxK(), t.stepK(), t.quickPicksK(), rates, pricing.check(t).warnings());
    }

    private static Rate rate(Currency currency, int fromK, long perMillionMinor) {
        return new Rate(fromK, CoinBaseRate.per100k(currency, perMillionMinor),
                CoinPriceTable.per100k(currency, perMillionMinor), perMillionMinor,
                CoinPriceTable.perMillion(currency, perMillionMinor));
    }

    private Limits limits() {
        return new Limits(CoinPriceTable.SMALLEST_STEP_K, CoinPriceTable.MAX_K_CAP, pricing.vendorMinTransferK(),
                CoinPriceTable.DEFAULT_QUICK_PICKS_K, CoinPriceTable.MAX_QUICK_PICKS, CoinPriceTable.MAX_BRACKETS);
    }

    private static CoinMarket market(String raw) {
        try {
            return CoinMarket.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new ApiExceptions.NotFoundException("No such coin price structure: " + raw);
        }
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(body);
    }
}
