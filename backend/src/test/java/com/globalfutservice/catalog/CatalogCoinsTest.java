package com.globalfutservice.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import com.globalfutservice.catalog.web.CatalogDtos;
import com.globalfutservice.coaching.CoachingSettingsService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.PriceUnit;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.pricing.PricingPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The catalogue and quotes read coins from their price structures, and only from there: a
 * coin row left live on the rate card is neither shown nor priced from.
 */
class CatalogCoinsTest {

    private final RateCardRepository repository = mock(RateCardRepository.class);
    private final CoinPricingService coins = mock(CoinPricingService.class);
    private CatalogService catalog;

    @BeforeEach
    void setUp() {
        AppProperties props = mock(AppProperties.class);
        AppProperties.Pricing pricing = mock(AppProperties.Pricing.class);
        when(props.season()).thenReturn("FC26");
        when(props.pricing()).thenReturn(pricing);
        when(pricing.enabledCurrencies()).thenReturn(List.of("INR", "USD"));
        catalog = new CatalogService(repository, props, mock(PricingPolicy.class), mock(CoachingSettingsService.class),
                mock(ListingSettings.class), coins);
    }

    private static RateCardEntity row(Sku sku, Platform platform, String variant, long price) {
        return new RateCardEntity("FC26", sku, platform, variant, Currency.INR,
                sku == Sku.TRADING_SERVICE ? PriceUnit.PER_MILLION : PriceUnit.FLAT, price,
                sku == Sku.TRADING_SERVICE ? new BigDecimal("0.01") : null,
                sku == Sku.TRADING_SERVICE ? BigDecimal.ONE : null,
                sku == Sku.TRADING_SERVICE ? new BigDecimal("0.01") : null, "label", 1, null);
    }

    @Test
    @DisplayName("coin options come from the structures; a stray coin row on the rate card is not shown")
    void catalogue() {
        when(repository.findLiveCurrencies("FC26")).thenReturn(List.of(Currency.INR));
        when(repository.findLiveForSeason("FC26", Currency.INR)).thenReturn(List.of(
                row(Sku.TRADING_SERVICE, Platform.PC, null, 999), row(Sku.BOOST_CHAMPS, null, "WINS_9", 120_000)));
        CatalogDtos.CatalogOption pc = new CatalogDtos.CatalogOption("PC", null, "PC", 1_300_000, "₹13,000.00",
                new BigDecimal("0.05"), BigDecimal.ONE, new BigDecimal("0.01"), null, false, null);
        when(coins.catalogOptions(Currency.INR)).thenReturn(List.of(pc));

        CatalogDtos.ServiceGroup trading = catalog.catalogue(Currency.INR).services().stream()
                .filter(g -> g.sku().equals("TRADING_SERVICE")).findFirst().orElseThrow();
        assertThat(trading.options()).containsExactly(pc);
        assertThat(trading.sellable()).isTrue();
    }

    @Test
    @DisplayName("a currency priced only for coins is still offered; one priced nowhere is not")
    void currencies() {
        when(repository.findLiveCurrencies("FC26")).thenReturn(List.of(Currency.INR));
        when(coins.pricedCurrencies("FC26")).thenReturn(Set.of(Currency.USD, Currency.GBP));
        assertThat(catalog.availableCurrencies()).containsExactly(Currency.INR, Currency.USD);
    }

    @Test
    @DisplayName("a coin quote is priced from the platform's structure, for the amount, never from the rate card")
    void coinQuote() {
        RateCard card = new RateCard("FC26", Sku.TRADING_SERVICE, Platform.XBOX, null, "Xbox",
                Money.ofMinor(1_200_000, Currency.INR), new BigDecimal("0.05"), BigDecimal.ONE,
                new BigDecimal("0.01"), 9L);
        when(coins.rateCard(Platform.XBOX, Currency.INR, new BigDecimal("0.6"))).thenReturn(card);

        assertThat(catalog.requireLiveRate(Sku.TRADING_SERVICE, Platform.XBOX, null, Currency.INR, new BigDecimal("0.6")))
                .isSameAs(card);
        verify(repository, never()).findLive(any(), any(), any(), any(), any());
    }
}
