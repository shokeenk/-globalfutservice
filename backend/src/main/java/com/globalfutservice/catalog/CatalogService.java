package com.globalfutservice.catalog;

import com.globalfutservice.catalog.web.CatalogDtos;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.loyalty.LoyaltyTier;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.web.ApiExceptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the live catalogue.
 *
 * <p>Which currencies the storefront offers is derived from which currencies have live
 * rate-card rows, intersected with the configured allow-list. Enabling a new market is
 * therefore an insert, not a deploy — and, importantly, a currency can never appear in
 * the picker without prices behind it.
 */
@Service
public class CatalogService {

    private final RateCardRepository repository;
    private final AppProperties props;
    private final PricingPolicy policy;

    public CatalogService(RateCardRepository repository, AppProperties props, PricingPolicy policy) {
        this.repository = repository;
        this.props = props;
        this.policy = policy;
    }

    @Transactional(readOnly = true)
    public CatalogDtos.CatalogResponse catalogue(Currency currency) {
        List<Currency> available = availableCurrencies();
        if (!available.contains(currency)) {
            throw new ApiExceptions.BadRequestException(
                    "unsupported_currency", "We are not accepting " + currency + " yet.");
        }

        List<RateCardEntity> rows = repository.findLiveForSeason(props.season(), currency);

        Map<Sku, List<CatalogDtos.CatalogOption>> bySku = new LinkedHashMap<>();
        for (RateCardEntity row : rows) {
            bySku.computeIfAbsent(row.getSku(), k -> new ArrayList<>()).add(
                    new CatalogDtos.CatalogOption(
                            row.getPlatform() == null ? null : row.getPlatform().name(),
                            row.getVariant(),
                            row.getLabel(),
                            row.getUnitPriceMinor(),
                            Money.ofMinor(row.getUnitPriceMinor(), row.getCurrency()).format(),
                            row.getMinQuantity(),
                            row.getMaxQuantity(),
                            row.getStepQuantity(),
                            // Null until achieved-versus-ordered outcomes are recorded.
                            // See CatalogOption.successRateBps for what turning this on
                            // requires; there is no data to aggregate today, and a
                            // placeholder here would ship as a advertised claim.
                            null));
        }

        List<CatalogDtos.ServiceGroup> services = new ArrayList<>();
        // Iterate the enum, not the map, so the storefront receives every service in a
        // stable order — including the ones that are priced but not yet sellable, which
        // is how the "Coming soon" cards render without being special-cased in the UI.
        for (Sku sku : Sku.values()) {
            List<CatalogDtos.CatalogOption> options = bySku.getOrDefault(sku, List.of());
            services.add(new CatalogDtos.ServiceGroup(
                    sku.name(),
                    sku.displayName(),
                    sku.sellable() && !options.isEmpty(),
                    sku.unit().name(),
                    sku.marketTaxApplies(),
                    sku.mayRequireCredentials(),
                    options));
        }

        return new CatalogDtos.CatalogResponse(
                props.season(),
                currency.name(),
                available.stream().map(Enum::name).toList(),
                services);
    }

    @Transactional(readOnly = true)
    public List<Currency> availableCurrencies() {
        List<Currency> live = repository.findLiveCurrencies(props.season());
        List<String> enabled = props.pricing().enabledCurrencies();
        List<Currency> out = live.stream().filter(c -> enabled.contains(c.name())).sorted().toList();
        return out.isEmpty() ? List.of(Currency.INR) : out;
    }

    @Transactional(readOnly = true)
    public RateCardEntity requireLiveRate(Sku sku, Platform platform, String variant, Currency currency) {
        return repository.findLive(props.season(), sku, platform, variant, currency)
                .orElseThrow(() -> new ApiExceptions.NotFoundException(
                        "That option is not available right now."));
    }

    /** Exposes the pricing and fulfilment policy so the storefront copy cannot drift. */
    public CatalogDtos.PolicyResponse policy() {
        AppProperties.Fulfilment f = props.fulfilment();
        return new CatalogDtos.PolicyResponse(
                policy.marketTaxBps(),
                policy.gatewayFeeBps(),
                policy.gatewayFeeMode().name(),
                policy.loyaltyCurrency().name(),
                policy.pointValueMinor(),
                policy.earnSpendUnitMinor(),
                policy.earnPointsPerUnit(),
                policy.maxWalletRedemptionBps(),
                policy.quoteTtl().toSeconds(),
                f.guaranteeWindow().toDays(),
                f.deliverySla().toHours(),
                f.refundFeeBps(),
                f.guaranteeCashBps(),
                f.guaranteeCreditBps(),
                f.defaultDeliveryMethod().name(),
                // Derived from the enum, so a change to the ladder reaches the rewards page
                // without anybody editing JSX.
                Arrays.stream(LoyaltyTier.values())
                        .map(t -> new CatalogDtos.TierView(
                                t.name(), t.displayName(), t.thresholdPoints(), t.discountBps()))
                        .toList(),
                policy.tierDiscountEnabled(),
                props.loyalty().dailyBonusPoints(),
                (int) props.coaching().sessionLength().toMinutes(),
                (int) props.coaching().blockSessionLength().toMinutes());
    }
}
