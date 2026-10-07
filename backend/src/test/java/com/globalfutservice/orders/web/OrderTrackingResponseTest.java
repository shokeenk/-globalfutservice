package com.globalfutservice.orders.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderPaymentState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the customer's tracking page and order list are told about a coin transfer: whether the
 * partner has it (the "Track your order" button), and how far it has got, from where the
 * vendor poller writes it.
 */
class OrderTrackingResponseTest {

    private final VendorOrderLedger ledger = mock(VendorOrderLedger.class);
    private OrderMapper mapper;
    private OrderEntity order;

    @BeforeEach
    void setUp() {
        OrderPaymentState payment = mock(OrderPaymentState.class);
        when(payment.of(any())).thenReturn(new OrderPaymentState.View(null, null));
        AppProperties props = mock(AppProperties.class);
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        mapper = new OrderMapper(new ObjectMapper(), props, mock(DiscordVerificationService.class),
                mock(DiscordBotClient.class), mock(CoachingService.class), ledger, payment);
        order = new OrderEntity("GFS-26-TRACK001", "q_1", "FC27", Sku.TRADING_SERVICE, null, null,
                new BigDecimal("0.5"), DeliveryMethod.PLAYER_AUCTION, Currency.INR, 10000, 10000,
                "{\"lines\":[],\"total\":{\"minor\":10000}}");
        ReflectionTestUtils.setField(order, "id", 7L);
        ReflectionTestUtils.setField(order, "status", OrderStatus.IN_PROGRESS);
    }

    @Test
    @DisplayName("before the partner has it: no Track button, no progress, and the vendor is not even asked")
    void beforeOnboarding() {
        ReflectionTestUtils.setField(order, "status", OrderStatus.READY_FOR_DELIVERY);
        OrderDtos.OrderResponse r = mapper.toResponse(order, List.of(), true);
        assertThat(r.transferStarted()).isFalse();
        assertThat(r.deliveredCoins()).isNull();
        assertThat(mapper.toAdminSummary(order, true).transferStarted()).isFalse();
        verify(ledger, never()).progress(anyLong());
    }

    @Test
    @DisplayName("with the partner: the Track button, and delivered of ordered as the poller last read it")
    void progressFromThePoller() {
        ReflectionTestUtils.setField(order, "transferStartedAt", Instant.parse("2026-10-07T10:00:00Z"));
        when(ledger.progress(7L)).thenReturn(Optional.of(new VendorOrderLedger.Progress(500, 200L)));

        OrderDtos.OrderResponse r = mapper.toResponse(order, List.of(), true);

        assertThat(r.transferStarted()).isTrue();
        assertThat(r.orderedCoins()).isEqualTo(500L);
        assertThat(r.deliveredCoins()).isEqualTo(200L);
        assertThat(mapper.toAdminSummary(order, true).transferStarted()).isTrue();
    }

    @Test
    @DisplayName("just accepted, nothing reported yet: the order's size, and nothing delivered")
    void startedNothingYet() {
        ReflectionTestUtils.setField(order, "transferStartedAt", Instant.parse("2026-10-07T10:00:00Z"));
        when(ledger.progress(7L)).thenReturn(Optional.of(new VendorOrderLedger.Progress(500, null)));
        OrderDtos.OrderResponse r = mapper.toResponse(order, List.of(), true);
        assertThat(r.orderedCoins()).isEqualTo(500L);
        assertThat(r.deliveredCoins()).isNull();
    }
}
