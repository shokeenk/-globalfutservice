package com.globalfutservice.payments;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ManualPaymentMethod;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A refund is recorded and the order refunded together, only where the state machine
 * already allowed a refund, and the customer's timeline never shows the staff's reason.
 */
class RefundServiceTest {

    private OrderService orders;
    private RefundRepository refunds;
    private RefundService service;
    private OrderEntity order;

    @BeforeEach
    void setUp() {
        orders = mock(OrderService.class);
        refunds = mock(RefundRepository.class);
        service = new RefundService(orders, refunds);
        order = mock(OrderEntity.class);
        when(order.getId()).thenReturn(9L);
        when(order.getTotalMinor()).thenReturn(102500L);
        when(order.getCurrency()).thenReturn(Currency.INR);
        when(order.total()).thenReturn(Money.ofMinor(102500, Currency.INR));
        when(order.getStatus()).thenReturn(OrderStatus.READY_FOR_DELIVERY);
        when(orders.requireAny("GFS-26-REFUND01")).thenReturn(order);
        when(refunds.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
    }

    @Test
    @DisplayName("records the full total and refunds the order, telling the customer how and not why")
    void records() {
        RefundEntity refund = service.record("GFS-26-REFUND01", ManualPaymentMethod.UPI, " 412345678901 ",
                "Customer abusive in ticket; do not serve again", 1L, "acc_admin");

        assertThat(refund.getAmountMinor()).isEqualTo(102500L);
        assertThat(refund.getCurrency()).isEqualTo(Currency.INR);
        assertThat(refund.getReference()).isEqualTo("412345678901");
        ArgumentCaptor<String> timeline = ArgumentCaptor.forClass(String.class);
        verify(orders).transition(eq(order), eq(OrderStatus.REFUNDED), eq(Actor.OPERATOR), eq(1L),
                eq("acc_admin"), timeline.capture());
        assertThat(timeline.getValue()).isEqualTo("Refunded ₹1,025.00 by UPI, reference 412345678901.");
        assertThat(timeline.getValue()).doesNotContain("abusive");
    }

    @Test
    @DisplayName("a second refund on the same order is refused, before and during a race")
    void once() {
        when(refunds.existsByOrderId(9L)).thenReturn(true);
        assertThatThrownBy(() -> service.record("GFS-26-REFUND01", ManualPaymentMethod.PAYPAL, "x", "y", 1L, "a"))
                .isInstanceOf(ApiExceptions.ConflictException.class).hasMessageContaining("already recorded");

        when(refunds.existsByOrderId(9L)).thenReturn(false);
        when(refunds.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("refund_order_uk"));
        assertThatThrownBy(() -> service.record("GFS-26-REFUND01", ManualPaymentMethod.PAYPAL, "x", "y", 1L, "a"))
                .isInstanceOf(ApiExceptions.ConflictException.class).hasMessageContaining("already recorded");
        verify(orders, never()).transition(any(), any(), any(), any(), anyString(), anyString());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"DRAFT", "AWAITING_PAYMENT", "ABANDONED", "DELIVERED",
            "COMPLETED", "REFUNDED", "CREDITED"})
    @DisplayName("refused wherever the state machine refuses a refund, saying why")
    void notRefundable(OrderStatus status) {
        when(order.getStatus()).thenReturn(status);

        assertThatThrownBy(() -> service.record("GFS-26-REFUND01", ManualPaymentMethod.UPI, "x", "y", 1L, "a"))
                .isInstanceOf(ApiExceptions.ConflictException.class)
                .hasMessage(RefundService.whyNot(status));
        verify(refunds, never()).saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(value = OrderStatus.class, names = {"PAID", "CREDENTIALS_PENDING", "READY_FOR_DELIVERY",
            "IN_PROGRESS", "ON_HOLD", "DISPUTED"})
    @DisplayName("allowed wherever an operator could already refund")
    void refundable(OrderStatus status) {
        when(order.getStatus()).thenReturn(status);
        service.record("GFS-26-REFUND01", ManualPaymentMethod.CRYPTO, "0xabc", "Could not deliver", 1L, "a");
        verify(refunds).saveAndFlush(any());
    }

    @Test
    @DisplayName("a delivered order is pointed at a dispute, as before")
    void deliveredNeedsDispute() {
        assertThat(RefundService.whyNot(OrderStatus.DELIVERED)).contains("dispute");
    }
}
