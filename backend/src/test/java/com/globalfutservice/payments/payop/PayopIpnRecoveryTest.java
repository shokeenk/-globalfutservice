package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.affiliate.AffiliateService;
import com.globalfutservice.coaching.AfterCommit;
import com.globalfutservice.coaching.CoachingService;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.credentials.CredentialVaultService;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.identity.AccountRepository;
import com.globalfutservice.loyalty.LoyaltyService;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.PaymentAlert;
import com.globalfutservice.notify.feed.CustomerFeedService;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderEventRepository;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.PaymentEntity;
import com.globalfutservice.payments.PaymentGateway;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.payments.WebhookLedger;
import com.globalfutservice.pricing.CouponService;
import com.globalfutservice.pricing.QuoteService;
import com.globalfutservice.scheduling.SchedulerLock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Client testing: a real Payop payment, complete in Payop's portal, never marked the order
 * paid. The IPN was refused at the address check (see TrustedClientAddressTest and
 * PayopControllerTest for the proxy chain). These are what follow a confirmed payment --
 * through the real {@link OrderService}, so "paid" means the order moves on to delivery --
 * and the reconciliation that catches a payment whose IPN never arrived or was refused.
 */
class PayopIpnRecoveryTest {

    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-07T10:00:00Z"), ZoneOffset.UTC);
    private static final String INVOICE = "d024f697-ba2d-456f-910e-4d7fdfd338dd";
    private static final String TXID = "dca59ca5-be19-470d-9494-9b76944e0241";
    private static final String REF = "GFS-26-EUR00001";

    private final PayopFakes.Invoices invoices = new PayopFakes.Invoices();
    private final PayopClient client = mock(PayopClient.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final com.globalfutservice.payments.ManualPaymentClaimRepository claims =
            mock(com.globalfutservice.payments.ManualPaymentClaimRepository.class);
    private final WebhookLedger ledger = mock(WebhookLedger.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final CredentialVaultService vault = mock(CredentialVaultService.class);
    private final SchedulerLock lock = mock(SchedulerLock.class);
    private final AppProperties props = mock(AppProperties.class);
    private final List<PaymentEntity> paymentRows = new ArrayList<>();
    private PayopCallbackService callbacks;
    private PayopReconciliation reconciliation;
    private OrderEntity order;
    private PayopInvoiceEntity attempt;

    @BeforeEach
    void setUp() {
        order = PayopFakes.order(7, REF, Currency.EUR, OrderStatus.AWAITING_PAYMENT);
        when(orders.findById(7L)).thenReturn(Optional.of(order));
        when(orders.save(any(OrderEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        attempt = invoices.open(7, INVOICE, 381, Currency.EUR, 9000, 407, "94.07", NOW.instant().minusSeconds(600));
        when(payments.save(any(PaymentEntity.class))).thenAnswer(inv -> {
            paymentRows.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(payments.findByProviderAndProviderPaymentId(eq("PAYOP"), anyString())).thenAnswer(inv ->
                paymentRows.stream().filter(p -> inv.getArgument(1).equals(p.getProviderPaymentId())).findFirst());
        when(payments.findByOrderId(anyLong())).thenAnswer(inv ->
                paymentRows.stream().filter(p -> p.getOrderId().equals(inv.getArgument(0))).toList());
        when(ledger.recordOrRetry(eq("PAYOP"), anyString(), anyString(), anyString())).thenReturn(Optional.of(1L));
        // The sign-in was given at checkout, so a paid coin order goes straight to delivery.
        when(vault.hasCredentials(7L)).thenReturn(true);

        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        when(props.payop()).thenReturn(PayopStartupCheckTest.payop(true, "pub", "secret", "jwt", "606", null));

        // The real order service: "paid" has to mean the order moves on, not that a mock was called.
        OrderService orderService = new OrderService(orders, mock(OrderEventRepository.class),
                mock(PaymentRepository.class), mock(PaymentGateway.class), mock(QuoteService.class),
                mock(LoyaltyService.class), mock(AffiliateService.class), vault, notifications,
                mock(AccountRepository.class), mock(CoachingService.class), mock(CouponService.class),
                mock(CustomerFeedService.class), new ObjectMapper(), props, NOW, AfterCommit.immediate());
        callbacks = new PayopCallbackService(invoices.repo, client, orders, orderService, payments, claims, ledger,
                notifications, props, new ObjectMapper(), PayopFakes.noTransactions(), NOW);
        reconciliation = new PayopReconciliation(invoices.repo, client, callbacks, lock, props, NOW);
    }

    private void payopConfirms(String amount, String currency) {
        when(client.transaction(TXID)).thenReturn(new PayopClient.Transaction(TXID, 2, null, REF,
                new BigDecimal(amount), currency, attempt.getAttemptId().toString()));
    }

    private byte[] ipn() {
        return """
                {"invoice":{"id":"%s","status":1,"txid":"%s","metadata":{"attemptId":"%s"}},
                 "transaction":{"id":"%s","state":2,"order":{"id":"%s"}}}
                """.formatted(INVOICE, TXID, attempt.getAttemptId(), TXID, REF).getBytes(StandardCharsets.UTF_8);
    }

    private List<PaymentAlert> alerts() {
        ArgumentCaptor<PaymentAlert> captor = ArgumentCaptor.forClass(PaymentAlert.class);
        verify(notifications, org.mockito.Mockito.atLeast(0)).paymentAlert(captor.capture());
        return captor.getAllValues();
    }

    @Nested
    @DisplayName("an IPN that reaches us")
    class Ipn {

        @Test
        @DisplayName("valid: PAID, then on to READY_FOR_DELIVERY, the customer's confirmation sent, one payment")
        void paidAndQueued() {
            payopConfirms("94.07", "EUR");
            PayopCallbackService.Outcome outcome = callbacks.handle(callbacks.parse(ipn()).orElseThrow());

            assertThat(outcome).isEqualTo(PayopCallbackService.Outcome.PAID);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
            assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.PAID);
            assertThat(paymentRows).hasSize(1);
            verify(notifications).orderConfirmed(any());
        }

        @Test
        @DisplayName("the same payment again (a repeat IPN, or the job after it): nothing more happens")
        void repeatIsNoOp() {
            payopConfirms("94.07", "EUR");
            callbacks.handle(callbacks.parse(ipn()).orElseThrow());
            // A repeat the ledger did not catch (a changed status code, say) still finds it applied.
            assertThat(callbacks.confirm(attempt.getId(), TXID)).isEqualTo(PayopCallbackService.Outcome.ALREADY_APPLIED);
            when(client.invoice(INVOICE)).thenReturn(new PayopClient.InvoiceInfo(1, TXID));
            assertThat(reconciliation.recheckOrder(7L)).isEmpty(); // paid: nothing left to check
            assertThat(paymentRows).hasSize(1);
            verify(notifications).orderConfirmed(any());
        }

        @Test
        @DisplayName("amount mismatch: held for review, staff alerted, never PAID")
        void mismatch() {
            payopConfirms("90.00", "EUR");
            assertThat(callbacks.handle(callbacks.parse(ipn()).orElseThrow()))
                    .isEqualTo(PayopCallbackService.Outcome.REVIEW);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
            assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.REVIEW);
            assertThat(attempt.getReviewReason()).isEqualTo("AMOUNT_MISMATCH");
            assertThat(alerts()).extracting(PaymentAlert::code).containsExactly("AMOUNT_MISMATCH");
            assertThat(paymentRows).isEmpty();
        }
    }

    @Nested
    @DisplayName("the reconciliation job: an IPN that never came, or was refused")
    class Reconciliation {

        @Test
        @DisplayName("Payop reports the invoice paid: its transaction confirmed, the order PAID and queued")
        void lostIpnRecovered() {
            when(client.invoice(INVOICE)).thenReturn(new PayopClient.InvoiceInfo(1, TXID));
            payopConfirms("94.07", "EUR");

            List<PayopReconciliation.Checked> checked = reconciliation.sweep();

            assertThat(checked).extracting(PayopReconciliation.Checked::outcome)
                    .containsExactly(PayopCallbackService.Outcome.PAID);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
            assertThat(paymentRows).hasSize(1);
            // The next run finds nothing left to do.
            assertThat(reconciliation.sweep()).isEmpty();
        }

        @Test
        @DisplayName("the same checks as an IPN: a wrong amount goes to review, never PAID")
        void sameChecks() {
            when(client.invoice(INVOICE)).thenReturn(new PayopClient.InvoiceInfo(1, TXID));
            payopConfirms("90.00", "EUR");
            assertThat(reconciliation.sweep()).extracting(PayopReconciliation.Checked::outcome)
                    .containsExactly(PayopCallbackService.Outcome.REVIEW);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
            // In review: the job leaves it to a person and does not alert again.
            assertThat(reconciliation.sweep()).isEmpty();
            assertThat(alerts()).hasSize(1);
        }

        @Test
        @DisplayName("paid, but Payop names no transaction: review and an alert, never PAID on the status alone")
        void paidWithoutTransaction() {
            when(client.invoice(INVOICE)).thenReturn(new PayopClient.InvoiceInfo(1, null));
            assertThat(reconciliation.sweep()).extracting(PayopReconciliation.Checked::outcome)
                    .containsExactly(PayopCallbackService.Outcome.REVIEW);
            assertThat(attempt.getReviewReason()).isEqualTo("PAID_NO_TRANSACTION");
            assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
            assertThat(alerts()).extracting(PaymentAlert::code).containsExactly("PAID_NO_TRANSACTION");
            verify(client, never()).transaction(anyString());
        }

        @Test
        @DisplayName("not paid yet, or Payop unreachable: nothing changes, and the next run asks again")
        void notYet() {
            when(client.invoice(INVOICE)).thenReturn(new PayopClient.InvoiceInfo(4, null));
            assertThat(reconciliation.sweep()).extracting(PayopReconciliation.Checked::outcome)
                    .containsExactly(PayopCallbackService.Outcome.PENDING);
            when(client.invoice(INVOICE)).thenThrow(new PayopClient.PayopException("get invoice",
                    PayopClient.ErrorCode.UNAVAILABLE, 0, List.of()));
            assertThat(reconciliation.sweep()).extracting(PayopReconciliation.Checked::outcome)
                    .containsExactly(PayopCallbackService.Outcome.UNAVAILABLE);
            assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.OPEN);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.AWAITING_PAYMENT);
        }

        @Test
        @DisplayName("looks at expired and replaced invoices from the last 26 hours -- Payop cannot cancel them -- "
                + "and at nothing older")
        void window() {
            attempt.expired(PayopInvoiceEntity.REPLACED, NOW.instant());
            PayopInvoiceEntity old = invoices.open(7, "0ld0ld00-0000-4000-8000-000000000000", 381, Currency.EUR,
                    9000, 407, "94.07", NOW.instant().minus(Duration.ofHours(27)));
            old.expired("LIFETIME", NOW.instant().minus(Duration.ofHours(3)));
            when(client.invoice(anyString())).thenReturn(new PayopClient.InvoiceInfo(4, null));

            assertThat(reconciliation.sweep()).extracting(PayopReconciliation.Checked::invoiceId)
                    .containsExactly(INVOICE);
        }

        @Test
        @DisplayName("runs only when Payop is on, and only one runner at a time")
        void scheduling() {
            reconciliation.scheduled();
            verify(lock).runExclusively(eq("payop-reconcile"), any());

            AppProperties off = mock(AppProperties.class);
            when(off.payop()).thenReturn(PayopStartupCheckTest.payop(false, null, null, null, null, null));
            SchedulerLock unused = mock(SchedulerLock.class);
            new PayopReconciliation(invoices.repo, client, callbacks, unused, off, NOW).scheduled();
            verify(unused, never()).runExclusively(anyString(), any());
        }
    }

    @Nested
    @DisplayName("an order staff moved on by hand before the payment was confirmed (the stuck test order)")
    class MovedByHand {

        @BeforeEach
        void movedOn() {
            org.springframework.test.util.ReflectionTestUtils.setField(order, "status", OrderStatus.READY_FOR_DELIVERY);
            when(client.invoice(INVOICE)).thenReturn(new PayopClient.InvoiceInfo(1, TXID));
            payopConfirms("94.07", "EUR");
        }

        @Test
        @DisplayName("nothing else recorded: held for a person with a plain question -- not called a duplicate to refund")
        void heldNotRefunded() {
            assertThat(reconciliation.recheckOrder(7L)).extracting(PayopReconciliation.Checked::outcome)
                    .containsExactly(PayopCallbackService.Outcome.REVIEW);
            assertThat(attempt.getReviewReason()).isEqualTo("ORDER_MOVED_BY_HAND");
            assertThat(alerts()).extracting(PaymentAlert::code).containsExactly("ORDER_MOVED_BY_HAND");
            assertThat(alerts().get(0).detail()).contains("accept it in Payop payments to record it");
            assertThat(paymentRows).isEmpty();
            assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        }

        @Test
        @DisplayName("staff accept it: the payment is recorded against the order as it stands, which is not moved again")
        void acceptedRecords() {
            reconciliation.recheckOrder(7L);
            assertThat(callbacks.acceptByHand(attempt.getId(), TXID, 1L)).isEqualTo(PayopCallbackService.Outcome.PAID);
            assertThat(paymentRows).hasSize(1);
            assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.PAID);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
            verify(notifications, never()).orderConfirmed(any());
        }

        @Test
        @DisplayName("but paid another way on record (a verified claim): a duplicate, refund needed")
        void paidOtherwiseIsDuplicate() {
            when(claims.findFirstByOrderIdAndStatusOrderByReviewedAtDesc(7L,
                    com.globalfutservice.domain.payments.ClaimStatus.VERIFIED))
                    .thenReturn(Optional.of(mock(com.globalfutservice.payments.ManualPaymentClaimEntity.class)));
            assertThat(reconciliation.recheckOrder(7L)).extracting(PayopReconciliation.Checked::outcome)
                    .containsExactly(PayopCallbackService.Outcome.DUPLICATE);
            assertThat(alerts()).extracting(PaymentAlert::code).containsExactly("DUPLICATE_PAYMENT");
        }
    }

    @Nested
    @DisplayName("staff's Re-check Payop payment")
    class Recheck {

        @Test
        @DisplayName("pays the stuck order through the same checks, whatever the invoice's age")
        void recoversTheStuckOrder() {
            attempt.expired("LIFETIME", NOW.instant()); // past the job's window is no obstacle here
            when(client.invoice(INVOICE)).thenReturn(new PayopClient.InvoiceInfo(1, TXID));
            payopConfirms("94.07", "EUR");

            assertThat(reconciliation.recheckOrder(7L)).extracting(PayopReconciliation.Checked::outcome)
                    .containsExactly(PayopCallbackService.Outcome.PAID);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.READY_FOR_DELIVERY);
        }

        @Test
        @DisplayName("a payment held for want of a transaction ID is looked at again, and paid once Payop names one")
        void reviewLookedAtAgain() {
            when(client.invoice(INVOICE)).thenReturn(new PayopClient.InvoiceInfo(1, null));
            reconciliation.sweep();
            assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.REVIEW);

            when(client.invoice(INVOICE)).thenReturn(new PayopClient.InvoiceInfo(1, TXID));
            payopConfirms("94.07", "EUR");
            assertThat(reconciliation.recheckOrder(7L)).extracting(PayopReconciliation.Checked::outcome)
                    .containsExactly(PayopCallbackService.Outcome.PAID);
            verify(vault).hasCredentials(anyLong());
        }
    }
}
