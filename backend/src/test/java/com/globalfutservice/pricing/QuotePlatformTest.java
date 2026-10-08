package com.globalfutservice.pricing;

import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.catalog.CatalogService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.QuoteSigner;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.pricing.web.QuoteDtos;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A coin quote is priced for the platform the customer chose, and only that one: never
 * given a platform, never priced from another platform's row.
 */
class QuotePlatformTest {

    private final CatalogService catalog = mock(CatalogService.class);
    private final QuoteService quotes = new QuoteService(catalog, mock(PricingEngine.class), mock(QuoteSigner.class),
            mock(LoyaltyService.class), mock(AffiliateService.class), mock(CouponService.class), Clock.systemUTC());

    private static QuoteDtos.QuoteRequest coins(String platform) {
        return new QuoteDtos.QuoteRequest("TRADING_SERVICE", platform, null, new BigDecimal("0.5"), "USD", null, null, 0L);
    }

    @Test
    @DisplayName("no platform: refused, and no price is looked up for any platform")
    void platformRequired() {
        for (String missing : new String[] {null, "", "  "}) {
            assertThatThrownBy(() -> quotes.quote(coins(missing), null))
                    .isInstanceOfSatisfying(ApiExceptions.BadRequestException.class,
                            e -> org.assertj.core.api.Assertions.assertThat(e.code()).isEqualTo("platform_required"));
        }
        verify(catalog, never()).requireLiveRate(any(), any(), any(), any(), any());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Platform.class)
    @DisplayName("the price is looked up for the platform the customer chose")
    void pricedForTheChosenPlatform(Platform chosen) {
        // Stop at the lookup: what matters is which platform it was asked for.
        when(catalog.requireLiveRate(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("looked up"));
        assertThatThrownBy(() -> quotes.quote(coins(chosen.name()), null)).hasMessage("looked up");
        // For that platform, and the amount asked for: a bracket can depend on it.
        verify(catalog).requireLiveRate(Sku.TRADING_SERVICE, chosen, null, Currency.USD, new BigDecimal("0.5"));
    }

    @Test
    @DisplayName("boosting and coaching are not priced per platform: their quote needs none")
    void flatServicesNeedNone() {
        when(catalog.requireLiveRate(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("looked up"));
        for (String sku : new String[] {"BOOST_CHAMPS", "COACHING"}) {
            assertThatThrownBy(() -> quotes.quote(new QuoteDtos.QuoteRequest(sku, null, "SINGLE_SESSION",
                    BigDecimal.ONE, "USD", null, null, 0L), null)).hasMessage("looked up");
        }
    }
}
