package com.globalfutservice.catalog;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The startup check that stops a season with nothing on sale from opening the shop.
 *
 * <p>Production moved {@code GFS_SEASON} to FC27 with only FC26 prices in the database;
 * the app started cleanly and checkout silently stopped. These pin the refusal and the
 * per-gap errors that replace that silence.
 */
class SeasonPriceCheckTest {

    private static final Set<Sku> ALL_SELLABLE = EnumSet.of(
            Sku.TRADING_SERVICE, Sku.BOOST_CHAMPS, Sku.BOOST_RIVALS, Sku.COACHING);

    /** For the cases where the season is fine and the list must not even be asked for. */
    private static final Supplier<List<String>> NOT_ASKED = () -> {
        throw new AssertionError("live seasons are only needed when refusing");
    };

    @Test
    @DisplayName("a season with no prices at all refuses to start, naming it and the seasons that work")
    void unpricedSeasonRefuses() {
        Map<Currency, Set<Sku>> priced = new LinkedHashMap<>();
        priced.put(Currency.INR, EnumSet.noneOf(Sku.class));
        priced.put(Currency.USD, EnumSet.noneOf(Sku.class));

        assertThatThrownBy(() -> SeasonPriceCheck.gaps("FC27", priced, () -> List.of("FC26")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GFS_SEASON is FC27, but nothing is priced for FC27")
                .hasMessageContaining("any service in INR, USD")
                .hasMessageContaining("Seasons with live prices: FC26")
                .hasMessageContaining("Refusing to start");
    }

    @Test
    @DisplayName("rows only for a service that is not on sale still count as nothing to sell")
    void unsellableRowsDoNotCount() {
        Map<Currency, Set<Sku>> priced = Map.of(Currency.INR, EnumSet.of(Sku.CARDS));

        assertThatThrownBy(() -> SeasonPriceCheck.gaps("FC27", priced, List::of))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Seasons with live prices: none");
    }

    @Test
    @DisplayName("the configured season is never offered as the way out")
    void configuredSeasonIsNotSuggested() {
        Map<Currency, Set<Sku>> priced = Map.of(Currency.INR, EnumSet.noneOf(Sku.class));

        // An unsellable row keeps FC27 in the live list; telling the operator to switch to
        // the season that just failed would be no help at all.
        assertThatThrownBy(() -> SeasonPriceCheck.gaps("FC27", priced, () -> List.of("FC26", "FC27")))
                .hasMessageContaining("Seasons with live prices: FC26\n");
    }

    @Test
    @DisplayName("a fully priced season has no gaps")
    void fullyPricedSeasonPasses() {
        Map<Currency, Set<Sku>> priced = new LinkedHashMap<>();
        priced.put(Currency.INR, ALL_SELLABLE);
        priced.put(Currency.EUR, ALL_SELLABLE);

        assertThat(SeasonPriceCheck.gaps("FC26", priced, NOT_ASKED)).isEmpty();
    }

    @Test
    @DisplayName("a partly priced season starts, with each missing service and currency named")
    void partialGapsAreNamed() {
        Map<Currency, Set<Sku>> priced = new LinkedHashMap<>();
        priced.put(Currency.INR, ALL_SELLABLE);
        priced.put(Currency.EUR, EnumSet.of(Sku.TRADING_SERVICE, Sku.BOOST_CHAMPS, Sku.BOOST_RIVALS));
        priced.put(Currency.GBP, EnumSet.noneOf(Sku.class));

        assertThat(SeasonPriceCheck.gaps("FC27", priced, NOT_ASKED)).containsExactly(
                "  - COACHING in EUR",
                "  - nothing is priced in GBP");
    }

    @Test
    @DisplayName("currencies are the configured ones, with rupees when none is usable")
    void currenciesFollowTheStorefront() {
        assertThat(SeasonPriceCheck.currencies(List.of("INR", "EUR", "XYZ", "EUR")))
                .containsExactly(Currency.INR, Currency.EUR);
        assertThat(SeasonPriceCheck.currencies(List.of("XYZ"))).containsExactly(Currency.INR);
        assertThat(SeasonPriceCheck.currencies(List.of())).containsExactly(Currency.INR);
    }

    @Test
    @DisplayName("verify reads the configured season and currencies and refuses when they are empty")
    void verifyUsesConfiguration() {
        RateCardRepository rates = mock(RateCardRepository.class);
        AppProperties props = mock(AppProperties.class);
        AppProperties.Pricing pricing = mock(AppProperties.Pricing.class);
        when(props.season()).thenReturn("FC27");
        when(props.pricing()).thenReturn(pricing);
        when(pricing.enabledCurrencies()).thenReturn(List.of("INR"));
        when(rates.findLiveForSeason("FC27", Currency.INR)).thenReturn(List.of());
        when(rates.findLiveSeasons()).thenReturn(List.of("FC26"));
        CoinPricingService coins = mock(CoinPricingService.class);

        assertThatThrownBy(() -> new SeasonPriceCheck(rates, props, coins).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("GFS_SEASON is FC27")
                .hasMessageContaining("Seasons with live prices: FC26");
    }

    @Test
    @DisplayName("verify passes a priced season without asking which other seasons exist")
    void verifyPassesWhenPriced() {
        RateCardRepository rates = mock(RateCardRepository.class);
        AppProperties props = mock(AppProperties.class);
        AppProperties.Pricing pricing = mock(AppProperties.Pricing.class);
        when(props.season()).thenReturn("FC26");
        when(props.pricing()).thenReturn(pricing);
        when(pricing.enabledCurrencies()).thenReturn(List.of("INR"));
        List<RateCardEntity> rows = ALL_SELLABLE.stream().map(SeasonPriceCheckTest::row).toList();
        when(rates.findLiveForSeason("FC26", Currency.INR)).thenReturn(rows);
        // Coins are on sale from their price structures, not from a rate card row.
        CoinPricingService coins = mock(CoinPricingService.class);
        when(coins.pricedCurrencies("FC26")).thenReturn(java.util.Set.of(Currency.INR));

        new SeasonPriceCheck(rates, props, coins).verify();

        verify(rates, never()).findLiveSeasons();
        verify(coins, never()).liveSeasons();
    }

    private static RateCardEntity row(Sku sku) {
        RateCardEntity row = mock(RateCardEntity.class);
        when(row.getSku()).thenReturn(sku);
        return row;
    }
}
