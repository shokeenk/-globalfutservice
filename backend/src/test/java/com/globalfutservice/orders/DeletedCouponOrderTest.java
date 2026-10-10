package com.globalfutservice.orders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.pricing.CustomerPricingContext;
import com.globalfutservice.domain.pricing.GatewayFeeMode;
import com.globalfutservice.domain.pricing.MarketTaxMode;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.domain.pricing.Quote;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A coupon deleted between the quote and the order: the discount no longer exists, so the
 * order is refused as a stale price -- the checkout prices again and says the code is not
 * valid -- and nothing is redeemed. It is never placed at the old discount.
 */
class DeletedCouponOrderTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final QuoteService quotes = mock(QuoteService.class);
    private final CouponService coupons = mock(CouponService.class);
    private final AppProperties props = mock(AppProperties.class);
    private final OrderService service = new OrderService(orders, mock(OrderEventRepository.class),
            mock(PaymentRepository.class), mock(PaymentGateway.class), quotes,
            mock(LoyaltyService.class), mock(AffiliateService.class), mock(CredentialVaultService.class),
            mock(NotificationService.class), mock(AccountRepository.class), mock(CoachingService.class),
            coupons, mock(CustomerFeedService.class), new ObjectMapper(), props, Clock.systemUTC(),
            AfterCommit.immediate(), mock(com.globalfutservice.fulfilment.AutoDispatchQueue.class));

    @Test
    @DisplayName("priced with a code since deleted: refused as a stale price, and no redemption is claimed")
    void refused() {
        PricingEngine engine = new PricingEngine(new PricingPolicy(500, MarketTaxMode.INCLUDED, 250,
                GatewayFeeMode.PASS_THROUGH, 2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true),
                Clock.systemUTC(), () -> "q_gone0001");
        RateCard card = new RateCard("FC26", Sku.TRADING_SERVICE, Platform.PLAYSTATION, null, "PlayStation",
                Money.ofMinor(1_300_000, Currency.INR), new BigDecimal("0.05"), new BigDecimal("1.00"),
                new BigDecimal("0.01"));
        Quote quote = engine.quote(card, new BigDecimal("0.10"),
                new CustomerPricingContext(0L, 0L, 0L, false, null, 0, "GONE10", 1_000));
        assertThat(quote.couponCode()).isEqualTo("GONE10");

        when(quotes.verifyOrThrow(any(), any())).thenReturn(quote);
        when(props.seasonYear()).thenReturn("26");
        when(props.fulfilment()).thenReturn(new Binder(new MapConfigurationPropertySource(java.util.Map.of(
                "f.backup-codes-required", "3"))).bind("f", AppProperties.Fulfilment.class).get());
        when(orders.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        OrderDtos.CreateOrderRequest request = mock(OrderDtos.CreateOrderRequest.class);
        when(request.email()).thenReturn("buyer@example.test");
        // Deleted: the code names no coupon any more.
        when(coupons.resolveIdFor("GONE10")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.create(request, null))
                .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                        e -> assertThat(e.code()).isEqualTo("quote_expired"));
        verify(coupons, never()).redeem(any(), anyString(), any(), any(), anyInt(), anyLong());
    }
}
