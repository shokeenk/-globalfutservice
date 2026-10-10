package com.globalfutservice.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.coaching.CoachEntity;
import com.globalfutservice.coaching.CoachRepository;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.coaching.CoachingSessionEntity;
import com.globalfutservice.coaching.CoachingSessionRepository;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.coaching.SessionActor;
import com.globalfutservice.domain.coaching.SessionStatus;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.DeliveryMethod;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ManualPaymentMethod;
import com.globalfutservice.fulfilment.VendorOrderLedger;
import com.globalfutservice.notify.DiscordBotClient;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderPaymentState;
import com.globalfutservice.orders.PayByDeadline;
import com.globalfutservice.orders.web.OrderDtos;
import com.globalfutservice.orders.web.OrderMapper;
import com.globalfutservice.payments.payop.PayopCheckoutService;
import com.globalfutservice.payments.payop.PayopInvoiceRepository;
import com.globalfutservice.payments.payop.PayopStartToken;
import com.globalfutservice.notify.discord.DiscordVerificationService;
import com.globalfutservice.web.ApiExceptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Completing the payment of an unpaid order: the same order, its frozen price, the
 * checkout's own payment routes, and one fee line per method.
 */
class ResumePaymentServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-06T12:00:00Z");

    private final ManualPaymentService manual = mock(ManualPaymentService.class);
    private final ManualPaymentClaimRepository claims = mock(ManualPaymentClaimRepository.class);
    private final PayopInvoiceRepository invoices = mock(PayopInvoiceRepository.class);
    private final PayopCheckoutService payop = mock(PayopCheckoutService.class);
    private final PayByDeadline payBy = mock(PayByDeadline.class);
    private final OrderPaymentState paymentState = mock(OrderPaymentState.class);
    private final CoachingService coaching = mock(CoachingService.class);
    private final CoachingSessionRepository sessions = mock(CoachingSessionRepository.class);
    private final CoachRepository coaches = mock(CoachRepository.class);
    private final CredentialVaultService vault = mock(CredentialVaultService.class);
    private final AppProperties props = mock(AppProperties.class, RETURNS_DEEP_STUBS);
    private final ResumePaymentService service;

    ResumePaymentServiceTest() {
        when(props.payop().enabled()).thenReturn(true);
        when(props.security().quoteSigningSecret()).thenReturn("a-quote-signing-secret-of-at-least-32-characters");
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        OrderMapper mapper = new OrderMapper(new ObjectMapper(), props, mock(DiscordVerificationService.class),
                mock(DiscordBotClient.class), coaching, mock(VendorOrderLedger.class), paymentState,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));
        when(paymentState.of(any())).thenReturn(new OrderPaymentState.View(OrderPaymentState.UNPAID,
                NOW.plusSeconds(3600)));
        when(payBy.forOrder(any())).thenReturn(NOW.plusSeconds(3600));
        when(manual.optionsFor(anyString())).thenReturn(List.of(
                new ManualPaymentService.PaymentOption(ManualPaymentMethod.UPI, "gfs@upi", "GFS", null),
                new ManualPaymentService.PaymentOption(ManualPaymentMethod.PAYPAL, "pay@gfs.test", null, null)));
        when(payop.claimsBlockedUntil(anyLong())).thenReturn(Optional.empty());
        when(payop.payableInvoice(any(), any())).thenReturn(Optional.empty());
        when(claims.findByOrderIdAndStatus(anyLong(), any())).thenReturn(Optional.empty());
        service = new ResumePaymentService(manual, claims, invoices, payop, new PayopStartToken(props, clock), payBy,
                paymentState, mapper, coaching, sessions, coaches, vault, props, clock);
    }

    /**
     * An order as placed: base 100.00, a 10.00 coupon, the flat 2.5% card fee of 2.25 -- a
     * total of 92.25, and 90.00 without the card fee.
     */
    private static OrderEntity order(long id, Sku sku, DeliveryMethod delivery, Currency currency, OrderStatus status) {
        String breakdown = """
                {"lines":[
                  {"code":"BASE","label":"Coins","amountMinor":10000,"amountFormatted":"100.00"},
                  {"code":"COUPON_DISCOUNT","label":"Coupon","amountMinor":-1000,"amountFormatted":"-10.00"},
                  {"code":"GATEWAY_FEE","label":"Payment processing (2.5%)","amountMinor":225,"amountFormatted":"2.25"}
                ]}
                """;
        OrderEntity order = new OrderEntity("GFS-26-RESUME0" + id, "q_" + id, "FC27", sku, null,
                sku == Sku.COACHING ? "SINGLE" : null, BigDecimal.ONE, delivery, currency, 10000, 9225, breakdown);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "status", status);
        order.setAccountId(42L);
        order.setGuestEmail("buyer@example.test");
        return order;
    }

    private static OrderEntity eur() {
        return order(1, Sku.TRADING_SERVICE, DeliveryMethod.PLAYER_AUCTION, Currency.EUR, OrderStatus.AWAITING_PAYMENT);
    }

    private static List<String> codes(ResumePaymentService.Breakdown b) {
        return b.lines().stream().map(OrderDtos.OrderLineDto::code).toList();
    }

    private void payopOffers(OrderEntity order) {
        when(payop.options(org.mockito.ArgumentMatchers.eq(order), anyString())).thenReturn(new PayopCheckoutService.Options(order.getCurrency(), 9000,
                List.of(new PayopCheckoutService.MethodOption(381, "Bank transfer", "bank_transfer", 407, 9407)), null));
    }

    @Nested
    @DisplayName("the order itself")
    class SameOrder {

        @Test
        @DisplayName("resuming keeps the same reference and the frozen price, in the order's own currency")
        void sameOrderFrozenPrice() {
            OrderEntity order = eur();
            ResumePaymentService.View view = service.view(order, "en");

            assertThat(view.publicRef()).isEqualTo("GFS-26-RESUME01");
            assertThat(view.currency()).isEqualTo("EUR");
            assertThat(view.amountDueMinor()).isEqualTo(9225);
            assertThat(view.amountDueFormatted()).isEqualTo(order.total().format());
            assertThat(view.manual().totalMinor()).isEqualTo(9225);
            assertThat(view.payBy()).isEqualTo(NOW.plusSeconds(3600));
            // Nothing about resuming touches the order's price or reference.
            assertThat(order.getTotalMinor()).isEqualTo(9225);
            assertThat(order.getPublicRef()).isEqualTo("GFS-26-RESUME01");
        }

        @Test
        @DisplayName("a Payop invoice open for the order: the amount due is the invoice's -- the method's fee, no 2.5%")
        void amountDueWithAnInvoiceOpen() {
            OrderEntity order = eur();
            order.choosePayopTerms("""
                    {"provider":"PAYOP","methodName":"Bank transfer","feeMinor":407,"netMinor":9000,"totalMinor":9407,
                     "payableUntil":"%s"}
                    """.formatted(NOW.plusSeconds(20 * 3600)));
            ResumePaymentService.View view = service.view(order, "en");

            assertThat(view.amountDueMinor()).isEqualTo(9407);
            // UPI, PayPal and crypto, once they can be used again, are still paid at the order's own total.
            assertThat(view.manual().totalMinor()).isEqualTo(9225);
            assertThat(codes(view.manual())).contains("GATEWAY_FEE");
        }

        @Test
        @DisplayName("Payop is offered for an order in any currency, INR included, whenever it is on")
        void payopForEveryCurrency() {
            OrderEntity inr = order(2, Sku.TRADING_SERVICE, DeliveryMethod.PLAYER_AUCTION, Currency.INR,
                    OrderStatus.AWAITING_PAYMENT);
            assertThat(service.view(inr, "en").payopOffered()).isTrue();
            assertThat(service.view(eur(), "en").payopOffered()).isTrue();
            // UPI on the same INR order keeps its 2.5% card fee.
            assertThat(codes(service.view(inr, "en").manual())).contains("GATEWAY_FEE");
        }

        @Test
        @DisplayName("an order not waiting for payment shows its state and offers nothing to pay")
        void notPending() {
            OrderEntity paid = order(3, Sku.TRADING_SERVICE, DeliveryMethod.PLAYER_AUCTION, Currency.EUR,
                    OrderStatus.READY_FOR_DELIVERY);
            ResumePaymentService.View view = service.view(paid, "en");
            assertThat(view.manualMethods()).isEmpty();
            assertThat(view.payopOffered()).isFalse();
            assertThat(view.manual()).isNull();
        }

        @Test
        @DisplayName("every payment action refuses an order that is not AWAITING_PAYMENT")
        void actionsRefuseOtherStates() {
            for (OrderStatus status : List.of(OrderStatus.ABANDONED, OrderStatus.PAID, OrderStatus.READY_FOR_DELIVERY,
                    OrderStatus.DELIVERED)) {
                OrderEntity o = order(4, Sku.COACHING, DeliveryMethod.SCHEDULED_SESSION, Currency.EUR, status);
                List<Consumer<OrderEntity>> actions = List.of(
                        x -> service.payopOptions(x, "DE"),
                        x -> service.startPayop(x, "token", "en"),
                        x -> service.submitClaim(x, ManualPaymentMethod.UPI, "UTR12345678"),
                        x -> service.attachProof(x, new byte[] {1}),
                        x -> service.holdSlot(x, null, null, null));
                for (Consumer<OrderEntity> action : actions) {
                    assertThatThrownBy(() -> action.accept(o))
                            .as(status.name())
                            .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                                    e -> assertThat(e.code()).isEqualTo("payment_not_pending"));
                }
            }
            verifyNoInteractions(manual);
            verify(payop, never()).start(any(), anyLong(), anyString(), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("the fee follows the method")
    class Fees {

        @Test
        @DisplayName("UPI, PayPal and crypto: the frozen lines with the 2.5% card fee, and no other fee")
        void manualFee() {
            ResumePaymentService.Breakdown manualBreakdown = service.view(eur(), "en").manual();
            assertThat(codes(manualBreakdown)).containsExactly("BASE", "COUPON_DISCOUNT", "GATEWAY_FEE");
            assertThat(manualBreakdown.totalMinor()).isEqualTo(9225);
        }

        @Test
        @DisplayName("a Payop method: its own fee in place of the 2.5% card fee, never both")
        void payopFee() {
            OrderEntity order = eur();
            payopOffers(order);
            ResumePaymentService.PayopOptions options = service.payopOptions(order, "de");
            ResumePaymentService.PayopMethod bank = options.methods().get(0);

            // The price every method's fee is added to, line by line: no 2.5% card fee among them.
            assertThat(options.lines()).extracting(OrderDtos.OrderLineDto::code)
                    .containsExactly("BASE", "COUPON_DISCOUNT");
            assertThat(codes(bank.breakdown())).containsExactly("BASE", "COUPON_DISCOUNT", "PAYMENT_FEE")
                    .doesNotContain("GATEWAY_FEE");
            assertThat(bank.breakdown().lines().get(2).label()).isEqualTo("Payment processing fee (Bank transfer)");
            assertThat(bank.breakdown().lines().get(2).amountMinor()).isEqualTo(407);
            assertThat(bank.breakdown().totalMinor()).isEqualTo(9407).isEqualTo(9000 + 407);
            assertThat(bank.token()).isNotBlank();
        }

        @Test
        @DisplayName("a Payop payment is started from the token's method and amount, never one the browser sends")
        void startsFromTheToken() {
            OrderEntity order = eur();
            payopOffers(order);
            String token = service.payopOptions(order, "DE").methods().get(0).token();
            when(payop.start(order, 381, "DE", 9407, "fr")).thenReturn(new PayopCheckoutService.Started(
                    "https://checkout.payop.com/fr/payment/invoice-preprocessing/i-1", "i-1", 9407,
                    NOW.plusSeconds(86400)));

            assertThat(service.startPayop(order, token, "fr").invoiceId()).isEqualTo("i-1");
            verify(payop).start(order, 381, "DE", 9407, "fr");
        }

        @Test
        @DisplayName("past the pay-by time no new invoice is opened; resuming never moves that time")
        void payByPassed() {
            OrderEntity order = eur();
            payopOffers(order);
            String token = service.payopOptions(order, "DE").methods().get(0).token();
            when(payBy.forOrder(order)).thenReturn(NOW.minusSeconds(1));

            assertThatThrownBy(() -> service.startPayop(order, token, "en"))
                    .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                            e -> assertThat(e.code()).isEqualTo("pay_by_passed"));
            verify(payop, never()).start(any(), anyLong(), anyString(), anyLong(), any());
        }
    }

    @Nested
    @DisplayName("manual methods")
    class Manual {

        @Test
        @DisplayName("a UTR goes through the checkout's own claim: the same ticket, email and Payop block")
        void sameClaim() {
            OrderEntity order = eur();
            service.submitClaim(order, ManualPaymentMethod.UPI, "UTR12345678");
            verify(manual).submit(order, ManualPaymentMethod.UPI, "UTR12345678");
        }

        @Test
        @DisplayName("while a Payop invoice is payable the claim is refused, and the page says until when and where")
        void blockedByPayop() {
            OrderEntity order = eur();
            Instant until = NOW.plusSeconds(20 * 3600);
            when(payop.claimsBlockedUntil(1L)).thenReturn(Optional.of(until));
            when(payop.payableInvoice(order, "en")).thenReturn(Optional.of(new PayopCheckoutService.PayableInvoice(
                    "i-1", "Bank transfer", 9407, Currency.EUR, until, "https://checkout.payop.com/en/x/i-1")));
            when(manual.submit(order, ManualPaymentMethod.UPI, "UTR12345678")).thenThrow(
                    new ApiExceptions.ConflictException("payop_invoice_open", "Finish it, or wait."));

            ResumePaymentService.View view = service.view(order, "en");
            assertThat(view.manualBlockedUntil()).isEqualTo(until);
            // UPI, PayPal and crypto are still priced with the 2.5% card fee.
            assertThat(codes(view.manual())).contains("GATEWAY_FEE");
            assertThat(view.manual().totalMinor()).isEqualTo(9225);
            assertThat(view.payableInvoice().url()).isEqualTo("https://checkout.payop.com/en/x/i-1");
            assertThatThrownBy(() -> service.submitClaim(order, ManualPaymentMethod.UPI, "UTR12345678"))
                    .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                            e -> assertThat(e.code()).isEqualTo("payop_invoice_open"));
        }
    }

    @Nested
    @DisplayName("what staff see")
    class Staff {

        @Test
        @DisplayName("the current method is the latest real attempt, with its fee; superseded invoices are marked")
        void currentMethod() {
            OrderEntity order = eur();
            com.globalfutservice.payments.payop.PayopInvoiceEntity open = invoice(order, 700001, "Wallet", 485,
                    NOW.minusSeconds(600), com.globalfutservice.payments.payop.PayopInvoiceEntity.Status.OPEN, null);
            com.globalfutservice.payments.payop.PayopInvoiceEntity replaced = invoice(order, 381, "Bank transfer", 407,
                    NOW.minusSeconds(1200), com.globalfutservice.payments.payop.PayopInvoiceEntity.Status.EXPIRED,
                    com.globalfutservice.payments.payop.PayopInvoiceEntity.REPLACED);
            when(invoices.findByOrderIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(open, replaced));
            when(claims.findByOrderIdOrderBySubmittedAtDesc(1L)).thenReturn(List.of());

            ResumePaymentService.StaffView view = service.staffView(order);

            assertThat(view.current().method()).isEqualTo("Wallet");
            assertThat(codes(view.currentBreakdown())).containsExactly("BASE", "COUPON_DISCOUNT", "PAYMENT_FEE");
            assertThat(view.currentBreakdown().totalMinor()).isEqualTo(9485);
            assertThat(view.attempts()).extracting(ResumePaymentService.Attempt::method,
                    ResumePaymentService.Attempt::superseded).containsExactly(
                    org.assertj.core.groups.Tuple.tuple("Wallet", false),
                    org.assertj.core.groups.Tuple.tuple("Bank transfer", true));
        }

        @Test
        @DisplayName("a UTR sent after the invoice makes the manual method current, with the 2.5% card fee")
        void claimAfterInvoice() {
            OrderEntity order = eur();
            com.globalfutservice.payments.payop.PayopInvoiceEntity open = invoice(order, 381, "Bank transfer", 407,
                    NOW.minusSeconds(26 * 3600), com.globalfutservice.payments.payop.PayopInvoiceEntity.Status.EXPIRED,
                    "EXPIRED");
            ManualPaymentClaimEntity claim = mock(ManualPaymentClaimEntity.class);
            when(claim.getMethod()).thenReturn(ManualPaymentMethod.UPI);
            when(claim.getStatus()).thenReturn(com.globalfutservice.domain.payments.ClaimStatus.SUBMITTED);
            when(claim.getSubmittedAt()).thenReturn(NOW.minusSeconds(60));
            when(invoices.findByOrderIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(open));
            when(claims.findByOrderIdOrderBySubmittedAtDesc(1L)).thenReturn(List.of(claim));

            ResumePaymentService.StaffView view = service.staffView(order);

            assertThat(view.current().kind()).isEqualTo(ResumePaymentService.Attempt.MANUAL);
            assertThat(view.current().method()).isEqualTo("UPI");
            assertThat(codes(view.currentBreakdown())).contains("GATEWAY_FEE").doesNotContain("PAYMENT_FEE");
        }

        private com.globalfutservice.payments.payop.PayopInvoiceEntity invoice(OrderEntity order, long methodId,
                String name, long fee, Instant created, com.globalfutservice.payments.payop.PayopInvoiceEntity.Status status,
                String reason) {
            com.globalfutservice.payments.payop.PayopInvoiceEntity a =
                    mock(com.globalfutservice.payments.payop.PayopInvoiceEntity.class);
            when(a.getMethodId()).thenReturn(methodId);
            when(a.getMethodName()).thenReturn(name);
            when(a.getFeeMinor()).thenReturn(fee);
            when(a.getTotalMinor()).thenReturn(9000 + fee);
            when(a.getCreatedAt()).thenReturn(created);
            when(a.getExpiresAt()).thenReturn(created.plusSeconds(24 * 3600));
            when(a.getInvoiceId()).thenReturn("inv-" + methodId);
            when(a.getStatus()).thenReturn(status);
            when(a.getReviewReason()).thenReturn(reason);
            return a;
        }
    }

    @Nested
    @DisplayName("coaching and coins")
    class BeforePaying {

        private final Instant slot = NOW.plusSeconds(3 * 86400);

        private OrderEntity coachingOrder() {
            return order(5, Sku.COACHING, DeliveryMethod.SCHEDULED_SESSION, Currency.EUR, OrderStatus.AWAITING_PAYMENT);
        }

        private CoachingSessionEntity session(SessionStatus status, Instant start, Instant holdExpiresAt) {
            CoachingSessionEntity s = mock(CoachingSessionEntity.class);
            when(s.getId()).thenReturn(90L);
            when(s.getStatus()).thenReturn(status);
            when(s.getCoachId()).thenReturn(3L);
            when(s.getStartsAt()).thenReturn(start);
            when(s.getEndsAt()).thenReturn(start.plusSeconds(3600));
            when(s.getCustomerTimezone()).thenReturn("Asia/Kolkata");
            when(s.getHoldExpiresAt()).thenReturn(holdExpiresAt);
            return s;
        }

        private void coach() {
            CoachEntity coach = mock(CoachEntity.class);
            when(coach.getPublicId()).thenReturn("coach_vinay");
            when(coach.getDisplayName()).thenReturn("Vinay");
            when(coaches.findById(3L)).thenReturn(Optional.of(coach));
        }

        @Test
        @DisplayName("an expired hold: the page is told which slot it was, to check it again")
        void expiredHoldShown() {
            coach();
            CoachingSessionEntity existing = session(SessionStatus.RELEASED, slot, NOW.minusSeconds(60));
            when(sessions.findByOrderIdOrderByIdAsc(5L)).thenReturn(List.of(existing));
            ResumePaymentService.CoachingSlot c = service.view(coachingOrder(), "en").coaching();
            assertThat(c.state()).isEqualTo(ResumePaymentService.CoachingSlot.EXPIRED);
            assertThat(c.coachId()).isEqualTo("coach_vinay");
            assertThat(c.startsAt()).isEqualTo(slot);
        }

        @Test
        @DisplayName("re-checking the same slot holds it again for the order")
        void recheckSameSlot() {
            coach();
            OrderEntity order = coachingOrder();
            CoachingSessionEntity existing = session(SessionStatus.RELEASED, slot, NOW.minusSeconds(60));
            when(sessions.findByOrderIdOrderByIdAsc(5L)).thenReturn(List.of(existing));
            CoachingSessionEntity held = session(SessionStatus.PENDING, slot, NOW.plusSeconds(7200));
            when(coaching.holdForOrder(42L, 5L, "SINGLE", "coach_vinay", slot, "Asia/Kolkata")).thenReturn(held);

            ResumePaymentService.CoachingSlot c = service.holdSlot(order, null, null, null);

            assertThat(c.state()).isEqualTo(ResumePaymentService.CoachingSlot.HELD);
            verify(coaching).releaseHoldForOrder(5L, SessionActor.SYSTEM, null, "hold expired");
            verify(coaching).holdForOrder(42L, 5L, "SINGLE", "coach_vinay", slot, "Asia/Kolkata");
        }

        @Test
        @DisplayName("taken in the meantime: the customer is told to pick another time")
        void slotTaken() {
            coach();
            CoachingSessionEntity existing = session(SessionStatus.RELEASED, slot, NOW.minusSeconds(60));
            when(sessions.findByOrderIdOrderByIdAsc(5L)).thenReturn(List.of(existing));
            when(coaching.holdForOrder(42L, 5L, "SINGLE", "coach_vinay", slot, "Asia/Kolkata")).thenThrow(
                    new ApiExceptions.ConflictException("slot_unavailable", "That slot was just taken."));

            assertThatThrownBy(() -> service.holdSlot(coachingOrder(), null, null, null))
                    .isInstanceOfSatisfying(ApiExceptions.ConflictException.class,
                            e -> assertThat(e.code()).isEqualTo("slot_unavailable"));
        }

        @Test
        @DisplayName("a newly picked slot is held instead")
        void repick() {
            coach();
            Instant picked = slot.plusSeconds(86400);
            CoachingSessionEntity existing = session(SessionStatus.RELEASED, slot, NOW.minusSeconds(60));
            when(sessions.findByOrderIdOrderByIdAsc(5L)).thenReturn(List.of(existing));
            CoachingSessionEntity newHold = session(SessionStatus.PENDING, picked, NOW.plusSeconds(7200));
            when(coaching.holdForOrder(42L, 5L, "SINGLE", "coach_vinay", picked, "Europe/London"))
                    .thenReturn(newHold);

            assertThat(service.holdSlot(coachingOrder(), "coach_vinay", picked, "Europe/London").startsAt())
                    .isEqualTo(picked);
        }

        @Test
        @DisplayName("a hold still running is left as it is")
        void stillHeld() {
            coach();
            CoachingSessionEntity existing = session(SessionStatus.PENDING, slot, NOW.plusSeconds(600));
            when(sessions.findByOrderIdOrderByIdAsc(5L)).thenReturn(List.of(existing));
            assertThat(service.holdSlot(coachingOrder(), null, null, null).state())
                    .isEqualTo(ResumePaymentService.CoachingSlot.HELD);
            verify(coaching, never()).holdForOrder(any(), any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("coins: a sign-in that is gone (purged, or never sent) is asked for again before paying")
        void signInAskedAgain() {
            OrderEntity coins = eur();
            when(vault.hasCredentials(1L)).thenReturn(false);
            assertThat(service.view(coins, "en").signInNeeded()).isTrue();
            when(vault.hasCredentials(1L)).thenReturn(true);
            assertThat(service.view(coins, "en").signInNeeded()).isFalse();
            assertThat(service.view(coachingOrder(), "en").signInNeeded()).as("coaching needs no sign-in").isFalse();
        }
    }
}
