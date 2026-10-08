package com.globalfutservice.admin;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.globalfutservice.catalog.CoinPriceStore;
import com.globalfutservice.catalog.CoinPricingService;
import com.globalfutservice.catalog.RateCardRepository;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.CoinMarket;
import com.globalfutservice.domain.catalog.CoinPriceTable;
import com.globalfutservice.domain.catalog.CoinPriceTable.Bracket;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Coin rates page as it stands, on top of the two structures: one base price per
 * currency, read from PC and written to both, everything else in each structure kept.
 */
class AdminRateCardCoinRatesTest {

    private static final AccountPrincipal ADMIN = new AccountPrincipal(7L, "acc_owner", "owner@example.test",
            AccountRole.ADMIN);

    private final CoinPriceStore store = mock(CoinPriceStore.class);
    private final Map<CoinMarket, CoinPriceStore.Version> live = new EnumMap<>(CoinMarket.class);
    private AdminRateCardController controller;

    @BeforeEach
    void setUp() {
        AppProperties props = mock(AppProperties.class);
        AppProperties.Pricing pricing = mock(AppProperties.Pricing.class);
        AppProperties.FutTransfer futTransfer = mock(AppProperties.FutTransfer.class);
        when(props.season()).thenReturn("FC26");
        when(props.pricing()).thenReturn(pricing);
        when(pricing.enabledCurrencies()).thenReturn(List.of("INR", "USD"));
        when(props.futTransfer()).thenReturn(futTransfer);
        when(futTransfer.order()).thenReturn(new AppProperties.FutTransferOrder(300, 1, 50, 0, "1", 0, "0", "0", 0,
                "-1", "-1"));
        controller = new AdminRateCardController(mock(RateCardRepository.class), props,
                new CoinPricingService(store, props));

        live.put(CoinMarket.PC, version(new CoinPriceTable(1L, "FC26", CoinMarket.PC, 50, 1000, 10, List.of(100),
                Map.of(Currency.INR, List.of(new Bracket(0, 1_300_000), new Bracket(500, 1_200_000)),
                        Currency.USD, List.of(new Bracket(0, 14_300))))));
        live.put(CoinMarket.CONSOLE, version(new CoinPriceTable(2L, "FC26", CoinMarket.CONSOLE, 50, 2000, 10,
                List.of(50, 500), Map.of(Currency.INR, List.of(new Bracket(0, 1_300_000)),
                        Currency.USD, List.of(new Bracket(0, 14_300))))));
        when(store.live("FC26")).thenAnswer(inv -> Map.copyOf(live));
        when(store.live(anyString(), any())).thenAnswer(inv -> java.util.Optional.ofNullable(live.get(inv.getArgument(1))));
        when(store.replace(any(), any())).thenAnswer(inv -> {
            CoinPriceTable t = inv.getArgument(0);
            CoinPriceStore.Version v = version(new CoinPriceTable(live.get(t.market()).table().version() + 10,
                    t.season(), t.market(), t.minK(), t.maxK(), t.stepK(), t.quickPicksK(), t.rates()));
            live.put(t.market(), v);
            return v;
        });
    }

    private static CoinPriceStore.Version version(CoinPriceTable t) {
        return new CoinPriceStore.Version(t, Instant.parse("2026-10-08T06:00:00Z"), null, null);
    }

    @Test
    @DisplayName("reads PC's base price per 100K, per currency")
    void reads() {
        var rates = controller.coinRates().getBody();
        assertThat(rates).extracting(AdminRateCardController.CoinRateDto::currency).containsExactly("INR", "USD");
        assertThat(rates.get(0).per100k()).isEqualByComparingTo("1300");
        assertThat(rates.get(0).perMillionMinor()).isEqualTo(1_300_000);
        assertThat(rates.get(1).per100k()).isEqualByComparingTo("14.3");
    }

    @Test
    @DisplayName("a save sets that base price in both structures, and leaves their ranges, quick picks and brackets")
    void writesBoth() {
        var after = controller.updateCoinRates(new AdminRateCardController.UpdateCoinRatesRequest(List.of(
                new AdminRateCardController.CoinRateInput("INR", new BigDecimal("1250")))), ADMIN).getBody();

        CoinPriceTable pc = live.get(CoinMarket.PC).table();
        assertThat(pc.rates().get(Currency.INR)).containsExactly(new Bracket(0, 1_250_000), new Bracket(500, 1_200_000));
        assertThat(pc.base(Currency.USD)).hasValue(14_300);
        assertThat(List.of(pc.maxK(), pc.quickPicksK())).containsExactly(1000, List.of(100));
        CoinPriceTable console = live.get(CoinMarket.CONSOLE).table();
        assertThat(console.rates().get(Currency.INR)).containsExactly(new Bracket(0, 1_250_000));
        assertThat(List.of(console.maxK(), console.quickPicksK())).containsExactly(2000, List.of(50, 500));
        assertThat(after.get(0).perMillionMinor()).isEqualTo(1_250_000);
    }

    @Test
    @DisplayName("the general rate endpoint no longer writes coin rows: nothing would read them")
    void noCoinRows() {
        assertThatThrownBy(() -> controller.update(new AdminRateCardController.UpdateRateRequest("TRADING_SERVICE",
                "PC", null, "INR", 1_300_000, null, null, null, null, null), ADMIN))
                .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class,
                        e -> assertThat(e.getMessage()).contains("coin pricing page"));
    }
}
