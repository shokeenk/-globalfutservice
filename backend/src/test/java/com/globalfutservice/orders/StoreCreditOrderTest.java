package com.globalfutservice.orders;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.pricing.CustomerPricingContext;
import com.globalfutservice.domain.pricing.GatewayFeeMode;
import com.globalfutservice.domain.pricing.MarketTaxMode;
import com.globalfutservice.domain.pricing.PricingEngine;
import com.globalfutservice.domain.pricing.PricingPolicy;
import com.globalfutservice.domain.pricing.Quote;
import com.globalfutservice.fulfilment.AutoDispatchQueue;
import com.globalfutservice.identity.AccountEntity;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.orders.result.OrderResultRepository;
import com.globalfutservice.orders.result.ResultRules;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.storecredit.StoreCreditService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Store credit when an order is placed: spent from the balance in the order's transaction,
 * and an order the credit covers in full is paid by the system there and then -- no payment
 * step, no payment intent -- and carries on as any paid order does.
 */
class StoreCreditOrderTest {

    private final OrderRepository orders = mock(OrderRepository.class);
    private final QuoteService quotes = mock(QuoteService.class);
    private final PaymentGateway gateway = mock(PaymentGateway.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final StoreCreditService storeCredit = mock(StoreCreditService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final AutoDispatchQueue autoDispatch = mock(AutoDispatchQueue.class);
    private final AppProperties props = mock(AppProperties.class);
    private final OrderService service = new OrderService(orders, mock(OrderEventRepository.class), payments, gateway,
            quotes, mock(LoyaltyService.class), mock(AffiliateService.class), mock(CredentialVaultService.class),
            notifications, mock(AccountRepository.class), mock(CoachingService.class), mock(CouponService.class),
            mock(CustomerFeedService.class), new ObjectMapper(), props, Clock.systemUTC(), AfterCommit.immediate(),
            autoDispatch, storeCredit, mock(ResultRules.class), mock(OrderResultRepository.class));

    private Quote champs(long credit) {
        PricingEngine engine = new PricingEngine(new PricingPolicy(500, MarketTaxMode.INCLUDED, 250,
                GatewayFeeMode.PASS_THROUGH, 2_000, Currency.INR, 100L, 200_000L, 20L, Duration.ofMinutes(10), true),
                Clock.systemUTC(), () -> "q_credit" + credit);
        RateCard card = new RateCard("FC26", Sku.BOOST_CHAMPS, null, "WINS_15", "15 wins · Elite I",
                Money.ofMinor(400_000, Currency.INR), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
        return engine.quote(card, BigDecimal.ONE, new CustomerPricingContext(0L, 0L, 0L, false, null, 0, null, 0, credit));
    }

    private OrderDtos.CreateOrderResponse place(Quote quote) {
        when(quotes.verifyOrThrow(any(), any())).thenReturn(quote);
        when(props.seasonYear()).thenReturn("26");
        when(props.fulfilment()).thenReturn(new Binder(new MapConfigurationPropertySource(Map.of(
                "f.backup-codes-required", "3"))).bind("f", AppProperties.Fulfilment.class).get());
        when(orders.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(orders.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(gateway.createOrder(anyString(), any(), anyString(), anyString()))
                .thenReturn(new PaymentGateway.GatewayOrder("stub_1", "STUB", quote.total().minor(), "INR"));
        OrderDtos.CreateOrderRequest request = mock(OrderDtos.CreateOrderRequest.class);
        when(request.email()).thenReturn("buyer@example.test");
        when(request.boostPlatform()).thenReturn("PLAYSTATION");
        AccountEntity account = mock(AccountEntity.class);
        when(account.getId()).thenReturn(42L);
        return service.create(request, account);
    }

    @Test
    @DisplayName("credit covering the whole order: spent, paid by the system, no payment intent")
    void coveredInFull() {
        Quote quote = champs(500_000);
        assertThat(quote.total().minor()).isZero();

        OrderDtos.CreateOrderResponse response = place(quote);

        verify(storeCredit).spend(eq(42L), any(), eq(Currency.INR), eq(400_000L), anyString());
        assertThat(response.payment()).as("no payment step").isNull();
        assertThat(response.totalMinor()).isZero();
        assertThat(response.status()).isNotEqualTo(OrderStatus.AWAITING_PAYMENT.name());
        verify(gateway, never()).createOrder(anyString(), any(), anyString(), anyString());
        verify(payments, never()).save(any());
        // Paid like any other: the customer's confirmation, and on into the queue.
        verify(notifications).orderConfirmed(any());
        verify(autoDispatch).paid(any());
    }

    @Test
    @DisplayName("credit covering part of it: spent, and the rest paid as usual")
    void coveredInPart() {
        OrderDtos.CreateOrderResponse response = place(champs(47_500));

        verify(storeCredit).spend(eq(42L), any(), eq(Currency.INR), eq(47_500L), anyString());
        assertThat(response.payment()).isNotNull();
        assertThat(response.status()).isEqualTo(OrderStatus.AWAITING_PAYMENT.name());
        assertThat(response.totalMinor()).as("₹3,525.00 and 2.5% of it").isEqualTo(361_313);
        verify(notifications, never()).orderConfirmed(any());
    }

    @Test
    @DisplayName("no credit: nothing is spent")
    void none() {
        place(champs(0));
        verify(storeCredit, never()).spend(any(), any(), any(), eq(0L), anyString());
        verify(storeCredit, never()).spend(any(), any(), any(), org.mockito.ArgumentMatchers.longThat(v -> v > 0),
                anyString());
    }
}
