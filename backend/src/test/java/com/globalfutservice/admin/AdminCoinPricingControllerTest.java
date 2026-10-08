package com.globalfutservice.admin;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.globalfutservice.catalog.CoinPriceStore;
import com.globalfutservice.catalog.CoinPricingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.CoinMarket;
import com.globalfutservice.domain.catalog.CoinPriceTable;
import com.globalfutservice.domain.catalog.CoinPriceTable.Bracket;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.pricing.GatewayFeeMode;
import com.globalfutservice.domain.pricing.MarketTaxMode;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.identity.AccountRole;
import com.globalfutservice.security.AccountPrincipal;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The coin pricing page's server side: prices typed per 100K and shown per 1M beside them,
 * a preview that prices real amounts with the real engine, warnings said and never
 * blocking, errors listed together, and quick picks defaulting to today's set.
 */
class AdminCoinPricingControllerTest {

    private final CoinPriceStore store = mock(CoinPriceStore.class);
    private AdminCoinPricingController controller;
    private static final AccountPrincipal ADMIN = new AccountPrincipal(7L, "acc_owner", "owner@example.test",
            AccountRole.ADMIN);

    @BeforeEach
    void setUp() {
        AppProperties props = mock(AppProperties.class);
        AppProperties.Pricing pricing = mock(AppProperties.Pricing.class);
        AppProperties.FutTransfer futTransfer = mock(AppProperties.FutTransfer.class);
        when(props.season()).thenReturn("FC26");
        when(props.pricing()).thenReturn(pricing);
        when(pricing.enabledCurrencies()).thenReturn(List.of("INR"));
        when(props.futTransfer()).thenReturn(futTransfer);
        when(futTransfer.order()).thenReturn(new AppProperties.FutTransferOrder(300, 1, 50, 0, "1", 0, "0", "0", 0,
                "-1", "-1"));
        // Production's policy: EA's 5% included in the price, the 2.5% card fee passed on.
        PricingEngine engine = new PricingEngine(new PricingPolicy(500, MarketTaxMode.INCLUDED, 250,
                GatewayFeeMode.PASS_THROUGH, 2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true),
                Clock.systemUTC(), () -> "q_preview");
        controller = new AdminCoinPricingController(new CoinPricingService(store, props), engine, props);
        when(store.replace(any(), any())).thenAnswer(inv -> {
            CoinPriceTable t = inv.getArgument(0);
            return new CoinPriceStore.Version(new CoinPriceTable(99L, t.season(), t.market(), t.minK(), t.maxK(),
                    t.stepK(), t.quickPicksK(), t.rates()), Instant.now(), null, "owner@example.test");
        });
    }

    private static AdminCoinPricingController.Draft draft(Integer minK, Integer maxK, List<Integer> picks,
                                                         AdminCoinPricingController.RateInput... rates) {
        return new AdminCoinPricingController.Draft(minK, maxK, 10, picks, List.of(rates), null);
    }

    private static AdminCoinPricingController.RateInput rupees(String per100k,
                                                                AdminCoinPricingController.BracketInput... brackets) {
        return new AdminCoinPricingController.RateInput("INR", new BigDecimal(per100k), List.of(brackets));
    }

    @Test
    @DisplayName("preview: each amount per 100K and per 1M, the coins alone and what a guest pays, and the warnings")
    void preview() {
        var body = controller.preview("console", draft(50, 1000, null,
                rupees("1300", new AdminCoinPricingController.BracketInput(500, new BigDecimal("1200"))))).getBody();

        assertThat(body.errors()).isEmpty();
        assertThat(body.warnings()).containsExactly("INR: 500K costs ₹6,000.00, less than 490K at ₹6,370.00. "
                + "Every amount from 470K to 490K costs more than 500K.");
        // The minimum, the default quick picks, the bracket and the step below it, the maximum.
        assertThat(body.rows()).extracting(AdminCoinPricingController.PreviewRow::amountK)
                .containsExactly(50, 100, 250, 490, 500, 1000);
        var hundredK = body.rows().get(1);
        assertThat(hundredK.per100k()).isEqualTo("1300");
        assertThat(hundredK.per100kFormatted()).isEqualTo("₹1,300.00");
        assertThat(hundredK.perMillionFormatted()).isEqualTo("₹13,000.00");
        assertThat(hundredK.coinPriceFormatted()).isEqualTo("₹1,300.00");
        // EA's 5%: included in the price, as the shop is configured, so before and after are the same.
        assertThat(hundredK.marketTaxIncluded()).isTrue();
        assertThat(hundredK.marketTaxLabel()).isEqualTo("EA transfer market tax (5%)");
        assertThat(hundredK.marketTaxFormatted()).isEqualTo("₹0.00");
        assertThat(hundredK.afterTaxFormatted()).isEqualTo("₹1,300.00");
        // What production quoted a guest for 100K on PC: ₹1,300 and the 2.5% card fee.
        assertThat(hundredK.guestTotalFormatted()).isEqualTo("₹1,332.50");
        var fiveHundredK = body.rows().get(4);
        assertThat(fiveHundredK.perMillionFormatted()).isEqualTo("₹12,000.00");
        assertThat(fiveHundredK.coinPriceFormatted()).isEqualTo("₹6,000.00");
        verify(store, never()).replace(any(), any());
    }

    @Test
    @DisplayName("preview: a price that looks typed in the wrong unit, next to the other structure, is said plainly")
    void wrongUnit() {
        CoinPriceTable console = new CoinPriceTable(5L, "FC26", CoinMarket.CONSOLE, 50, 1000, 10, List.of(100),
                Map.of(Currency.INR, List.of(new Bracket(0, 1_300_000))));
        when(store.live("FC26")).thenReturn(Map.of(CoinMarket.CONSOLE,
                new CoinPriceStore.Version(console, Instant.now(), null, null)));

        var body = controller.preview("PC", draft(50, 1000, null, rupees("13"))).getBody();
        assertThat(body.errors()).isEmpty();
        assertThat(body.warnings()).anyMatch(w -> w.startsWith("INR: the base price, ₹13.00 per 100K")
                && w.contains("less than a fifth of PlayStation + Xbox's ₹1,300.00 per 100K")
                && w.contains("typed in the wrong unit"));
    }

    @Test
    @DisplayName("preview: a minimum below 50K is allowed, with the reason it may not deliver")
    void lowMinimum() {
        var body = controller.preview("PC", draft(20, 1000, List.of(20, 100), rupees("1300"))).getBody();
        assertThat(body.errors()).isEmpty();
        assertThat(body.warnings()).containsExactly("Orders below 50K may not deliver properly with the current "
                + "FUT Transfer settings: its minimum per transfer is 50K, and this minimum is 20K.");
        assertThat(body.rows()).extracting(AdminCoinPricingController.PreviewRow::amountK).containsExactly(20, 100, 1000);
    }

    @Test
    @DisplayName("preview: everything wrong at once, and nothing priced")
    void previewErrors() {
        var body = controller.preview("PC", new AdminCoinPricingController.Draft(null, 1000, 10, List.of(), List.of(
                new AdminCoinPricingController.RateInput("XYZ", BigDecimal.ONE, null),
                new AdminCoinPricingController.RateInput("INR", new BigDecimal("0"), null)), null)).getBody();
        assertThat(body.errors()).contains("Set the minimum.", "Unknown currency: XYZ.",
                "INR base price: The INR price must be greater than zero.");
        assertThat(body.rows()).isEmpty();
    }

    @Test
    @DisplayName("save: per 100K typed, per 1M stored; quick picks left out become today's set within the range")
    void save() {
        var saved = controller.save("CONSOLE", draft(50, 400, null,
                rupees("1250", new AdminCoinPricingController.BracketInput(300, new BigDecimal("1200.50")))), ADMIN)
                .getBody();

        ArgumentCaptor<CoinPriceTable> stored = ArgumentCaptor.forClass(CoinPriceTable.class);
        verify(store).replace(stored.capture(), eq(7L));
        CoinPriceTable t = stored.getValue();
        assertThat(t.version()).isNull();
        assertThat(t.season()).isEqualTo("FC26");
        assertThat(t.market()).isEqualTo(CoinMarket.CONSOLE);
        assertThat(t.quickPicksK()).containsExactly(50, 100, 250);
        assertThat(t.rates().get(Currency.INR)).containsExactly(new Bracket(0, 1_250_000), new Bracket(300, 1_200_500));

        // What comes back: the version, and each price both ways.
        assertThat(saved.version()).isEqualTo(99L);
        assertThat(saved.platforms()).containsExactly("PLAYSTATION", "XBOX");
        var inr = saved.rates().get(0);
        assertThat(inr.base().per100kFormatted()).isEqualTo("₹1,250.00");
        assertThat(inr.base().perMillionFormatted()).isEqualTo("₹12,500.00");
        assertThat(inr.brackets().get(0).per100kFormatted()).isEqualTo("₹1,200.50");
        assertThat(inr.brackets().get(0).perMillionFormatted()).isEqualTo("₹12,005.00");
    }

    @Test
    @DisplayName("save: an explicit empty list means no quick picks")
    void noQuickPicks() {
        controller.save("PC", draft(50, 1000, List.of(), rupees("1300")), ADMIN);
        ArgumentCaptor<CoinPriceTable> stored = ArgumentCaptor.forClass(CoinPriceTable.class);
        verify(store).replace(stored.capture(), anyLong());
        assertThat(stored.getValue().quickPicksK()).isEmpty();
    }

    @Test
    @DisplayName("save: refused with every reason when it cannot be sold, and nothing stored")
    void saveRefused() {
        assertThatThrownBy(() -> controller.save("PC", draft(50, 20_000, List.of(100, 100), rupees("1300")), ADMIN))
                .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class, e -> assertThat(e.getMessage())
                        .contains("The maximum can be at most 10M.")
                        .contains("Quick pick 100K is listed twice."));
        verify(store, never()).replace(any(), any());
    }

    @Test
    @DisplayName("overview: every currency the shop sells in, with any missing base price shown as missing")
    void overview() {
        CoinPriceTable live = new CoinPriceTable(5L, "FC26", CoinMarket.PC, 50, 1000, 10, List.of(100),
                Map.of(Currency.USD, List.of(new Bracket(0, 14_300))));
        when(store.live("FC26")).thenReturn(Map.of(CoinMarket.PC, new CoinPriceStore.Version(live, Instant.now(), null, null)));

        var body = controller.overview().getBody();
        assertThat(body.season()).isEqualTo("FC26");
        var pc = body.structures().get(0);
        assertThat(pc.rates()).extracting(AdminCoinPricingController.CurrencyRates::currency).containsExactly("INR", "USD");
        assertThat(pc.rates().get(0).base()).isNull();
        assertThat(pc.rates().get(1).base().per100kFormatted()).isEqualTo("$14.30");
        assertThat(pc.rates().get(1).base().perMillionFormatted()).isEqualTo("$143.00");
    }
}
