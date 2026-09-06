package com.globalfutservice.payments;

import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.domain.payments.ManualPaymentMethod;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What a customer saying "I paid" is allowed to do.
 *
 * <p>The answer is: write one row, and nothing else. Everything in this file exists to
 * pin that down, because the failure it guards against is not a crash. If submitting a
 * claim ever advanced an order, the system would hand out coins, boosts and coaching to
 * anybody who typed twelve digits into a box, and every test of the happy path would
 * still pass while it happened.
 */
class ManualPaymentServiceTest {

    private ManualPaymentClaimRepository claims;
    private OrderService orderService;
    private ManualPaymentService service;

    private static final AppProperties.ManualPayments DESTINATIONS =
            new AppProperties.ManualPayments(
                    "coins@bank", "Coins Account",
                    "services@bank", "Services Account",
                    "pay@example.com", "https://paypal.example/x", "TWALLET");

    @BeforeEach
    void setUp() {
        claims = mock(ManualPaymentClaimRepository.class);
        orderService = mock(OrderService.class);

        AppProperties props = mock(AppProperties.class);
        when(props.manualPayments()).thenReturn(DESTINATIONS);

        when(claims.findByOrderIdAndStatus(anyLong(), any())).thenReturn(Optional.empty());
        when(claims.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));

        service = new ManualPaymentService(claims, orderService, props);
    }

    private static OrderEntity order(OrderStatus status, Sku sku) {
        OrderEntity order = mock(OrderEntity.class);
        when(order.getId()).thenReturn(7L);
        when(order.getStatus()).thenReturn(status);
        when(order.getSku()).thenReturn(sku);
        when(order.getPublicRef()).thenReturn("GFS-26-TEST");
        return order;
    }

    @Nested
    @DisplayName("submitting a claim")
    class Submitting {

        @Test
        @DisplayName("does not mark the order paid")
        void doesNotPayTheOrder() {
            OrderEntity awaiting = order(OrderStatus.AWAITING_PAYMENT, Sku.TRADING_SERVICE);

            service.submit(awaiting, ManualPaymentMethod.UPI, "123456789012");

            // The whole point. A customer's typed string must never reach markPaid.
            verify(orderService, never()).markPaid(any(), anyString());
        }

        @Test
        @DisplayName("records the destination the server chose, not one the caller picked")
        void recordsServerChosenDestination() {
            OrderEntity coins = order(OrderStatus.AWAITING_PAYMENT, Sku.TRADING_SERVICE);

            ManualPaymentClaimEntity claim =
                    service.submit(coins, ManualPaymentMethod.UPI, "123456789012");

            assertThat(claim.getDestination()).isEqualTo("coins@bank");
            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.SUBMITTED);
        }

        @Test
        @DisplayName("sends a coaching order to the services account, not the coins one")
        void coachingUsesServicesAccount() {
            OrderEntity coaching = order(OrderStatus.AWAITING_PAYMENT, Sku.COACHING);

            assertThat(service.submit(coaching, ManualPaymentMethod.UPI, "123456789012")
                    .getDestination()).isEqualTo("services@bank");
        }

        @Test
        @DisplayName("trims the reference but does not reshape it")
        void trimsOnly() {
            OrderEntity awaiting = order(OrderStatus.AWAITING_PAYMENT, Sku.COACHING);

            // A TRON txid is hex, a PayPal id is alphanumeric and a UTR is digits. Anything
            // that "cleans up" the value risks rewriting a reference an operator then
            // cannot find on a statement.
            assertThat(service.submit(awaiting, ManualPaymentMethod.CRYPTO, "  9f3c2ab7  ")
                    .getReference()).isEqualTo("9f3c2ab7");
        }

        @Test
        @DisplayName("is refused on an order that is not awaiting payment")
        void refusedWhenNotAwaitingPayment() {
            OrderEntity alreadyPaid = order(OrderStatus.PAID, Sku.TRADING_SERVICE);

            assertThatThrownBy(() ->
                    service.submit(alreadyPaid, ManualPaymentMethod.UPI, "123456789012"))
                    .isInstanceOf(ApiExceptions.ConflictException.class);
        }

        @Test
        @DisplayName("is refused for a method with no configured address")
        void refusedForUnconfiguredMethod() {
            AppProperties bare = mock(AppProperties.class);
            when(bare.manualPayments()).thenReturn(new AppProperties.ManualPayments(
                    null, null, null, null, null, null, null));
            ManualPaymentService noDestinations =
                    new ManualPaymentService(claims, orderService, bare);

            assertThatThrownBy(() -> noDestinations.submit(
                    order(OrderStatus.AWAITING_PAYMENT, Sku.COACHING),
                    ManualPaymentMethod.UPI, "123456789012"))
                    .isInstanceOf(ApiExceptions.BadRequestException.class);
        }

        @Test
        @DisplayName("is refused when the reference is too short to be one")
        void refusedWhenReferenceIsJunk() {
            OrderEntity awaiting = order(OrderStatus.AWAITING_PAYMENT, Sku.COACHING);

            assertThatThrownBy(() -> service.submit(awaiting, ManualPaymentMethod.UPI, "  1 "))
                    .isInstanceOf(ApiExceptions.BadRequestException.class);
        }

        @Test
        @DisplayName("replaces a pending claim rather than adding a second one")
        void resubmissionReplaces() {
            ManualPaymentClaimEntity stale = new ManualPaymentClaimEntity(
                    7L, ManualPaymentMethod.UPI, "coins@bank", "000000000000");
            when(claims.findByOrderIdAndStatus(7L, ClaimStatus.SUBMITTED))
                    .thenReturn(Optional.of(stale));

            service.submit(order(OrderStatus.AWAITING_PAYMENT, Sku.TRADING_SERVICE),
                    ManualPaymentMethod.UPI, "123456789012");

            // Two pending claims on one order would leave an operator guessing which
            // reference to look for on the statement.
            verify(claims).delete(stale);
        }
    }

    @Nested
    @DisplayName("offering payment methods")
    class Offering {

        @Test
        @DisplayName("offers one UPI destination plus PayPal and crypto")
        void offersThree() {
            List<ManualPaymentService.PaymentOption> options =
                    service.optionsFor("TRADING_SERVICE");

            assertThat(options).hasSize(3);
            assertThat(options).extracting(ManualPaymentService.PaymentOption::method)
                    .containsExactly(ManualPaymentMethod.UPI,
                            ManualPaymentMethod.PAYPAL,
                            ManualPaymentMethod.CRYPTO);
            // Never both UPI accounts at once: a customer choosing between two indistinct
            // "UPI" panels has a 50% chance of paying the wrong one.
            assertThat(options).extracting(ManualPaymentService.PaymentOption::destination)
                    .containsExactly("coins@bank", "pay@example.com", "TWALLET");
        }

        @Test
        @DisplayName("PayPal is offered on the account, and carries the scan link alongside it")
        void paypalCarriesBoth() {
            ManualPaymentService.PaymentOption paypal = service.optionsFor("COACHING").stream()
                    .filter(o -> o.method() == ManualPaymentMethod.PAYPAL)
                    .findFirst().orElseThrow();

            // The destination is the account, because that is what an operator reconciles
            // against and the only form somebody can pay by hand. The link is the extra.
            assertThat(paypal.destination()).isEqualTo("pay@example.com");
            assertThat(paypal.link()).isEqualTo("https://paypal.example/x");
        }

        @Test
        @DisplayName("PayPal survives having no scan link")
        void paypalWithoutLink() {
            AppProperties emailOnly = mock(AppProperties.class);
            when(emailOnly.manualPayments()).thenReturn(new AppProperties.ManualPayments(
                    null, null, null, null, "pay@example.com", null, null));

            // An account with no link is payable; a link with no account is not. Dropping
            // PayPal here would take away the method over a missing convenience.
            List<ManualPaymentService.PaymentOption> options =
                    new ManualPaymentService(claims, orderService, emailOnly).optionsFor("COACHING");

            assertThat(options).singleElement()
                    .satisfies(option -> {
                        assertThat(option.method()).isEqualTo(ManualPaymentMethod.PAYPAL);
                        assertThat(option.destination()).isEqualTo("pay@example.com");
                        assertThat(option.link()).isNull();
                    });
        }

        @Test
        @DisplayName("omits a method with no address rather than offering an empty one")
        void omitsUnconfigured() {
            AppProperties partial = mock(AppProperties.class);
            when(partial.manualPayments()).thenReturn(new AppProperties.ManualPayments(
                    null, null, null, null, null, null, "TWALLET"));

            assertThat(new ManualPaymentService(claims, orderService, partial)
                    .optionsFor("COACHING"))
                    .extracting(ManualPaymentService.PaymentOption::method)
                    .containsExactly(ManualPaymentMethod.CRYPTO);
        }
    }

    @Nested
    @DisplayName("reviewing a claim")
    class Reviewing {

        private ManualPaymentClaimEntity pending() {
            return new ManualPaymentClaimEntity(
                    7L, ManualPaymentMethod.UPI, "coins@bank", "123456789012");
        }

        @Test
        @DisplayName("verifying is what marks the order paid")
        void verifyingPays() {
            ManualPaymentClaimEntity claim = pending();
            OrderEntity awaiting = order(OrderStatus.AWAITING_PAYMENT, Sku.TRADING_SERVICE);
            when(claims.findById(1L)).thenReturn(Optional.of(claim));
            when(orderService.requireById(7L)).thenReturn(awaiting);

            service.verify(1L, 99L, "seen on statement");

            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.VERIFIED);
            assertThat(claim.getReviewedBy()).isEqualTo(99L);
            assertThat(claim.getReviewedAt()).isNotNull();
            verify(orderService).markPaid(awaiting, "UPI 123456789012");
        }

        @Test
        @DisplayName("rejecting leaves the order alone so the customer can try again")
        void rejectingDoesNotTouchTheOrder() {
            ManualPaymentClaimEntity claim = pending();
            when(claims.findById(1L)).thenReturn(Optional.of(claim));

            service.reject(1L, 99L, "nothing arrived");

            assertThat(claim.getStatus()).isEqualTo(ClaimStatus.REJECTED);
            verify(orderService, never()).markPaid(any(), anyString());
        }

        @Test
        @DisplayName("a claim cannot be reviewed twice")
        void doubleReviewIsRefused() {
            ManualPaymentClaimEntity claim = pending();
            claim.review(ClaimStatus.VERIFIED, 99L, null);
            when(claims.findById(1L)).thenReturn(Optional.of(claim));

            // Two operators working the same queue is normal. Both clicking Verify should
            // not run markPaid twice.
            assertThatThrownBy(() -> service.verify(1L, 100L, null))
                    .isInstanceOf(ApiExceptions.ConflictException.class);
            verify(orderService, never()).markPaid(any(), anyString());
        }
    }
}
