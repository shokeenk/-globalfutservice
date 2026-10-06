package com.globalfutservice.orders;

import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.payments.ManualPaymentClaimEntity;
import com.globalfutservice.payments.ManualPaymentClaimRepository;
import com.globalfutservice.payments.payop.PayopInvoiceEntity;
import com.globalfutservice.payments.payop.PayopInvoiceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** What the order page is told about payment: unpaid until a time, being checked, or expired. */
class OrderPaymentStateTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private final ManualPaymentClaimRepository claims = mock(ManualPaymentClaimRepository.class);
    private final PayopInvoiceRepository invoices = mock(PayopInvoiceRepository.class);
    private final PayByDeadline payBy = mock(PayByDeadline.class);
    private final OrderPaymentState state = new OrderPaymentState(claims, invoices, payBy,
            Clock.fixed(NOW, ZoneOffset.UTC));

    private OrderEntity order(OrderStatus status, Instant deadline) {
        OrderEntity order = mock(OrderEntity.class);
        when(order.getId()).thenReturn(1L);
        when(order.getStatus()).thenReturn(status);
        when(payBy.forOrder(order)).thenReturn(deadline);
        when(claims.findByOrderIdAndStatus(1L, ClaimStatus.SUBMITTED)).thenReturn(Optional.empty());
        when(invoices.payableUntil(anyLong(), any(), any())).thenReturn(Optional.empty());
        return order;
    }

    @Test
    @DisplayName("unpaid: payable until its deadline")
    void unpaid() {
        Instant deadline = NOW.plusSeconds(3600);
        assertThat(state.of(order(OrderStatus.AWAITING_PAYMENT, deadline)))
                .isEqualTo(new OrderPaymentState.View(OrderPaymentState.UNPAID, deadline));
    }

    @Test
    @DisplayName("payment details sent and not yet checked: submitted, whatever the time")
    void submitted() {
        OrderEntity o = order(OrderStatus.AWAITING_PAYMENT, NOW.minusSeconds(3600));
        when(claims.findByOrderIdAndStatus(1L, ClaimStatus.SUBMITTED))
                .thenReturn(Optional.of(mock(ManualPaymentClaimEntity.class)));
        assertThat(state.of(o).state()).isEqualTo(OrderPaymentState.SUBMITTED);
    }

    @Test
    @DisplayName("abandoned, or past its time with nothing Payop can still take: expired")
    void expired() {
        assertThat(state.of(order(OrderStatus.ABANDONED, NOW)).state()).isEqualTo(OrderPaymentState.EXPIRED);
        assertThat(state.of(order(OrderStatus.AWAITING_PAYMENT, NOW.minusSeconds(1))).state())
                .isEqualTo(OrderPaymentState.EXPIRED);
    }

    @Test
    @DisplayName("past its time but with a Payop invoice still payable: still unpaid, the invoice can finish it")
    void invoiceStillPayable() {
        OrderEntity o = order(OrderStatus.AWAITING_PAYMENT, NOW.minusSeconds(1));
        when(invoices.payableUntil(1L, NOW, PayopInvoiceEntity.Status.CREATING))
                .thenReturn(Optional.of(NOW.plusSeconds(600)));
        assertThat(state.of(o).state()).isEqualTo(OrderPaymentState.UNPAID);
    }

    @Test
    @DisplayName("paid and later: no payment state, the order page shows the order")
    void paid() {
        for (OrderStatus s : new OrderStatus[] {OrderStatus.PAID, OrderStatus.READY_FOR_DELIVERY, OrderStatus.DELIVERED}) {
            assertThat(state.of(order(s, NOW))).isEqualTo(OrderPaymentState.View.NONE);
        }
    }
}
