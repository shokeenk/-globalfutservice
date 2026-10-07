package com.globalfutservice.catalog;

import com.globalfutservice.coaching.CoachingSettingsService;
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

    public CatalogService(RateCardRepository repository, AppProperties props, PricingPolicy policy,
                          CoachingSettingsService coachingSettings, ListingSettings listingSettings) {
        this.listingSettings = listingSettings;
        this.repository = repository;
        this.props = props;
        this.policy = policy;
        this.coachingSettings = coachingSettings;
    }

    /** The session lengths the storefront shows are the admin's, not configuration's. */
    private final CoachingSettingsService coachingSettings;

    /** Success rates and Best Value, as set on the Listings page. */
    private final ListingSettings listingSettings;

    @Transactional(readOnly = true)
    public CatalogDtos.CatalogResponse catalogue(Currency currency) {
        List<Currency> available = availableCurrencies();
        if (!available.contains(currency)) {
            throw new ApiExceptions.BadRequestException(
                    "unsupported_currency", "We are not accepting " + currency + " yet.");
        }

        List<RateCardEntity> rows = repository.findLiveForSeason(props.season(), currency);

        // Best Value per boosting service: the admin's choice, else its last tier.
        Map<Sku, java.util.Optional<String>> bestValue = new java.util.EnumMap<>(Sku.class);
        Map<Sku, Map<String, ListingSettings.Stored>> stored = new java.util.EnumMap<>(Sku.class);
        for (Sku sku : ListingService.BOOSTING) {
            List<String> inOrder = rows.stream().filter(r -> r.getSku() == sku && r.getVariant() != null)
                    .map(RateCardEntity::getVariant).toList();
            bestValue.put(sku, listingSettings.resolveBestValue(sku, inOrder));
            stored.put(sku, listingSettings.forSku(sku));
        }

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
                            /*
                             * Published from configuration, not computed.
                             *
                             * Nothing in this application records what rank an order
                             * actually reached, so this is not a measurement -- it is a
                             * figure the business stands behind. Null for every variant
                             * nobody has set one for, which is most of them.
                             */
                            ListingService.BOOSTING.contains(row.getSku())
                                    ? listingSettings.successRate(row.getVariant(),
                                            stored.get(row.getSku()).get(row.getVariant()))
                                    : props.boosting().successRateBpsFor(row.getVariant()),
                            row.getVariant() != null && bestValue.getOrDefault(row.getSku(), java.util.Optional.empty())
                                    .map(row.getVariant()::equals).orElse(false)));
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
                (int) coachingSettings.current().singleSession().toMinutes(),
                (int) coachingSettings.current().blockSession().toMinutes(),
                // The same three conditions RazorpayGateway.isEnabled checks.
                props.razorpay().enabled()
                        && props.razorpay().keyId() != null && !props.razorpay().keyId().isBlank()
                        && props.razorpay().keySecret() != null && !props.razorpay().keySecret().isBlank(),
                props.notifications().emailEnabled(),
                f.backupCodesRequired());
    }
}
