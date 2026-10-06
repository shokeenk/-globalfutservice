package com.globalfutservice.orders.jobs;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.orders.PayByDeadline;
import com.globalfutservice.payments.ManualPaymentClaimEntity;
import com.globalfutservice.payments.ManualPaymentClaimRepository;
import com.globalfutservice.payments.payop.PayopInvoiceEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * When the abandoned-checkout sweep may close an unpaid order: 48 hours after it was placed,
 * or, when staff reject the customer's payment after that, 24 hours after the rejection.
 */
class UnpaidOrderSweepTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private final ManualPaymentClaimRepository claims = mock(ManualPaymentClaimRepository.class);
    private final AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final PayByDeadline payBy;

    UnpaidOrderSweepTest() {
        when(props.fulfilment().deliverySla()).thenReturn(Duration.ofHours(48));
        payBy = new PayByDeadline(claims, props);
    }

    private OrderEntity order(long id, Instant createdAt) {
        OrderEntity order = mock(OrderEntity.class);
        when(order.getId()).thenReturn(id);
        when(order.getCreatedAt()).thenReturn(createdAt);
        when(order.getPublicRef()).thenReturn("GFS-26-SWEEP00" + id);
        when(claims.findFirstByOrderIdAndStatusOrderByReviewedAtDesc(id, ClaimStatus.REJECTED))
                .thenReturn(Optional.empty());
        return order;
    }

    private void rejectedAt(long orderId, Instant when) {
        ManualPaymentClaimEntity claim = mock(ManualPaymentClaimEntity.class);
        when(claim.getReviewedAt()).thenReturn(when);
        when(claims.findFirstByOrderIdAndStatusOrderByReviewedAtDesc(orderId, ClaimStatus.REJECTED))
                .thenReturn(Optional.of(claim));
    }

    @Test
    @DisplayName("pay by: 48 hours after the order was placed")
    void fortyEightHours() {
        Instant placed = NOW.minus(Duration.ofHours(10));
        assertThat(payBy.forOrder(order(1, placed))).isEqualTo(placed.plus(Duration.ofHours(48)));
    }

    @Test
    @DisplayName("a rejection before the deadline leaves it where it was")
    void earlyRejection() {
        Instant placed = NOW.minus(Duration.ofHours(30));
        OrderEntity order = order(2, placed);
        rejectedAt(2, placed.plus(Duration.ofHours(20)));
        assertThat(payBy.forOrder(order)).isEqualTo(placed.plus(Duration.ofHours(48)));
    }

    @Test
    @DisplayName("a rejection after the deadline gives 24 hours from the rejection to pay again")
    void lateRejection() {
        Instant placed = NOW.minus(Duration.ofHours(60));
        OrderEntity order = order(3, placed);
        Instant rejected = placed.plus(Duration.ofHours(55));
        rejectedAt(3, rejected);
        assertThat(payBy.forOrder(order)).isEqualTo(rejected.plus(Duration.ofHours(24)));
    }

    @Test
    @DisplayName("the sweep closes orders past their deadline and leaves the one still inside its 24 hours")
    void sweep() {
        Instant placed = NOW.minus(Duration.ofHours(72));
        OrderEntity plain = order(4, placed);
        OrderEntity justRejected = order(5, placed);
        rejectedAt(5, NOW.minus(Duration.ofHours(2)));
        OrderEntity rejectedLongAgo = order(6, placed);
        rejectedAt(6, NOW.minus(Duration.ofHours(25)));

        OrderRepository orders = mock(OrderRepository.class);
        when(orders.findStaleUnpaid(NOW.minus(Duration.ofHours(48)), NOW, PayopInvoiceEntity.Status.CREATING,
                ClaimStatus.SUBMITTED)).thenReturn(List.of(plain, justRejected, rejectedLongAgo));
        OrderService orderService = mock(OrderService.class);

        new OrderLifecycleJobs(orders, orderService, props, payBy, Clock.fixed(NOW, ZoneOffset.UTC))
                .sweepAbandoned();

        verify(orderService).transition(eq(plain), eq(OrderStatus.ABANDONED), eq(Actor.SYSTEM), isNull(),
                anyString(), anyString());
        verify(orderService).transition(eq(rejectedLongAgo), eq(OrderStatus.ABANDONED), eq(Actor.SYSTEM),
                isNull(), anyString(), anyString());
        verify(orderService, never()).transition(eq(justRejected), any(), any(), any(), any(), any());
    }
}
