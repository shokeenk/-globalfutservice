package com.globalfutservice.payments;

import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.coaching.SessionActor;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.domain.payments.ManualPaymentMethod;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What reviewing a coaching order's payment does to the slot it picked at checkout:
 * proof keeps it, rejection frees it, and neither can fail the payment step itself.
 */
class ManualPaymentCoachingHoldTest {

    private static final Instant CREATED = Instant.parse("2026-09-26T10:00:00Z");

    private ManualPaymentClaimRepository claims;
    private CoachingService coaching;
    private ManualPaymentService service;

    @BeforeEach
    void setUp() {
        claims = mock(ManualPaymentClaimRepository.class);
        when(claims.findByOrderIdAndStatus(anyLong(), any())).thenReturn(Optional.empty());
        when(claims.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
        when(props.manualPayments()).thenReturn(new AppProperties.ManualPayments(
                "coins@bank", "Coins Account", "services@bank", "Services Account",
                "pay@example.com", "https://paypal.example/x", "TWALLET"));
        when(props.fulfilment().deliverySla()).thenReturn(Duration.ofHours(48));
        coaching = mock(CoachingService.class);
        service = new ManualPaymentService(claims, mock(ManualPaymentProofRepository.class),
                mock(OrderService.class), mock(com.globalfutservice.credentials.CredentialVaultService.class),
                mock(com.globalfutservice.notify.NotificationService.class),
                mock(CustomerFeedService.class), props, coaching);
    }

    private static OrderEntity order(Sku sku) {
        OrderEntity order = mock(OrderEntity.class);
        when(order.getId()).thenReturn(7L);
        when(order.getStatus()).thenReturn(OrderStatus.AWAITING_PAYMENT);
        when(order.getSku()).thenReturn(sku);
        when(order.getPublicRef()).thenReturn("GFS-26-TEST");
        when(order.getCreatedAt()).thenReturn(CREATED);
        when(order.getServiceLabel()).thenReturn("Coaching");
        when(order.total()).thenReturn(com.globalfutservice.domain.money.Money.ofMinor(
                100000L, com.globalfutservice.domain.money.Currency.INR));
        return order;
    }

    @Test
    @DisplayName("proof on a coaching order keeps its slot until review, up to the 48-hour cut-off")
    void proofExtendsTheHold() {
        service.submit(order(Sku.COACHING), ManualPaymentMethod.UPI, "123456789012");

        verify(coaching).extendHoldForOrder(7L, CREATED.plus(Duration.ofHours(48)));
    }

    @Test
    @DisplayName("proof on a coin order touches no coaching slot")
    void coinsLeftAlone() {
        service.submit(order(Sku.TRADING_SERVICE), ManualPaymentMethod.UPI, "123456789012");

        verify(coaching, never()).extendHoldForOrder(anyLong(), any());
    }

    @Test
    @DisplayName("a rejected payment frees the slot the order was holding")
    void rejectionFreesTheSlot() {
        ManualPaymentClaimEntity claim = new ManualPaymentClaimEntity(7L, ManualPaymentMethod.UPI,
                "services@bank", "123456789012");
        when(claims.findById(3L)).thenReturn(Optional.of(claim));

        service.reject(3L, 9L, "no such transfer");

        verify(coaching).releaseHoldForOrder(7L, SessionActor.OPERATOR, 9L, "payment rejected");
    }

    @Test
    @DisplayName("a hold that cannot be extended never fails the customer's submission")
    void holdFailureIsContained() {
        doThrow(new IllegalStateException("optimistic lock")).when(coaching)
                .extendHoldForOrder(anyLong(), any());

        ManualPaymentClaimEntity claim =
                service.submit(order(Sku.COACHING), ManualPaymentMethod.UPI, "123456789012");

        assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
    }

    @Test
    @DisplayName("a hold that cannot be released never fails the rejection")
    void releaseFailureIsContained() {
        ManualPaymentClaimEntity claim = new ManualPaymentClaimEntity(7L, ManualPaymentMethod.UPI,
                "services@bank", "123456789012");
        when(claims.findById(3L)).thenReturn(Optional.of(claim));
        doThrow(new IllegalStateException("optimistic lock")).when(coaching)
                .releaseHoldForOrder(anyLong(), any(), any(), any());

        assertThat(service.reject(3L, 9L, "no such transfer").getStatus())
                .isEqualTo(ClaimStatus.REJECTED);
    }
}
