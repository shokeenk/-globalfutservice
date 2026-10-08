package com.globalfutservice.admin;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.catalog.CatalogService;
import com.globalfutservice.catalog.CoinPriceStore;
import com.globalfutservice.catalog.CoinPricingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.CoinMarket;
import com.globalfutservice.domain.catalog.CoinPriceTable;
import com.globalfutservice.domain.catalog.CoinPriceTable.Bracket;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.pricing.GatewayFeeMode;
import com.globalfutservice.domain.pricing.MarketTaxMode;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.domain.pricing.QuoteSigner;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.pricing.web.QuoteDtos;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The coin pricing page's preview calculator shows exactly what a customer's quote charges
 * for the same structure, amount and currency: the coins before EA's market tax, the tax,
 * the price after it, and a guest's total with the card fee. Checked with the tax included
 * in the price, as the shop runs, and added on top, so "before and after" stays honest
 * whichever way it is configured.
 */
class CoinPreviewParityTest {

    private static final CoinPriceTable LIVE = new CoinPriceTable(5L, "FC26", CoinMarket.CONSOLE, 50, 1000, 10,
            List.of(100), Map.of(
                    Currency.INR, List.of(new Bracket(0, 1_300_000), new Bracket(500, 1_200_000)),
                    Currency.USD, List.of(new Bracket(0, 14_300))));

    @ParameterizedTest(name = "market tax {0}")
    @EnumSource(MarketTaxMode.class)
    void previewEqualsTheQuote(MarketTaxMode mode) {
        AppProperties props = mock(AppProperties.class);
        AppProperties.Pricing pricing = mock(AppProperties.Pricing.class);
        AppProperties.FutTransfer futTransfer = mock(AppProperties.FutTransfer.class);
        when(props.season()).thenReturn("FC26");
        when(props.pricing()).thenReturn(pricing);
        when(pricing.enabledCurrencies()).thenReturn(List.of("INR", "USD"));
        when(props.futTransfer()).thenReturn(futTransfer);
        when(futTransfer.order()).thenReturn(new AppProperties.FutTransferOrder(300, 1, 50, 0, "1", 0, "0", "0", 0,
                "-1", "-1"));
        CoinPriceStore store = mock(CoinPriceStore.class);
        when(store.live("FC26")).thenReturn(Map.of(CoinMarket.CONSOLE, new CoinPriceStore.Version(LIVE, Instant.now(),
                null, null)));
        when(store.live(eq("FC26"), eq(CoinMarket.CONSOLE))).thenReturn(java.util.Optional.of(
                new CoinPriceStore.Version(LIVE, Instant.now(), null, null)));
        CoinPricingService coins = new CoinPricingService(store, props);
        PricingEngine engine = new PricingEngine(new PricingPolicy(500, mode, 250, GatewayFeeMode.PASS_THROUGH, 2_000,
                Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true), Clock.systemUTC(), () -> "q_parity");

        // The quote path, as a customer reaches it: the catalogue hands the coin structure's row.
        CatalogService catalog = mock(CatalogService.class);
        when(catalog.requireLiveRate(eq(Sku.TRADING_SERVICE), any(), any(), any(), any())).thenAnswer(inv ->
                coins.rateCard(inv.getArgument(1), inv.getArgument(3), inv.getArgument(4)));
        QuoteService quotes = new QuoteService(catalog, engine,
                new QuoteSigner("test-only-quote-secret-00000000000000000000000000000"), mock(LoyaltyService.class),
                mock(AffiliateService.class), mock(CouponService.class), Clock.systemUTC());

        // The page's draft is the live structure, unchanged, as the admin opens it.
        var draft = new AdminCoinPricingController.Draft(50, 1000, 10, List.of(100), List.of(
                new AdminCoinPricingController.RateInput("INR", new java.math.BigDecimal("1300"), List.of(
                        new AdminCoinPricingController.BracketInput(500, new java.math.BigDecimal("1200")))),
                new AdminCoinPricingController.RateInput("USD", new java.math.BigDecimal("14.30"), List.of())),
                List.of(50, 100, 490, 500, 730, 1000));
        var rows = new AdminCoinPricingController(coins, engine, props).preview("CONSOLE", draft).getBody().rows();
        assertThat(rows).hasSize(12);

        for (var row : rows) {
            for (Platform platform : CoinMarket.CONSOLE.platforms()) {
                QuoteDtos.SignedQuote quote = quotes.quote(new QuoteDtos.QuoteRequest("TRADING_SERVICE",
                        platform.name(), null, CoinPriceTable.millions(row.amountK()), row.currency(), null, null, 0L),
                        null);
                String where = row.currency() + " " + row.amountK() + "K on " + platform;
                assertThat(row.coinPriceMinor()).as(where).isEqualTo(line(quote, "BASE"));
                assertThat(row.marketTaxMinor()).as(where).isEqualTo(line(quote, "MARKET_TAX"));
                assertThat(row.afterTaxMinor()).as(where).isEqualTo(quote.subtotalMinor());
                assertThat(row.guestTotalMinor()).as(where).isEqualTo(quote.totalMinor());
                assertThat(row.marketTaxIncluded()).isEqualTo(mode == MarketTaxMode.INCLUDED);
            }
        }
        if (mode == MarketTaxMode.ADDED) {
            // ₹1,300 for 100K, 5% on top: ₹65.
            var hundredK = rows.stream().filter(r -> r.currency().equals("INR") && r.amountK() == 100).findFirst().orElseThrow();
            assertThat(hundredK.marketTaxMinor()).isEqualTo(6_500);
            assertThat(hundredK.afterTaxMinor()).isEqualTo(136_500);
        }
    }

    private static long line(QuoteDtos.SignedQuote quote, String code) {
        return quote.lines().stream().filter(l -> l.code().equals(code)).mapToLong(QuoteDtos.QuoteLineDto::amountMinor)
                .findFirst().orElse(0);
    }
}
