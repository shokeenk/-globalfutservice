package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.PaymentStatus;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.PaymentAlert;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.PaymentEntity;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.payments.WebhookLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IPNs and what follows them. The IPN bodies are the shape of Payop's documented example;
 * the transaction answers are what Payop's API returns, parsed by the real client in
 * {@link PayopClientTest} -- here they are given directly.
 */
class PayopCallbackServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Clock NOW = Clock.fixed(Instant.parse("2026-10-04T12:00:00Z"), ZoneOffset.UTC);
    private static final String INVOICE = "d024f697-ba2d-456f-910e-4d7fdfd338dd";
    private static final String TXID = "dca59ca5-be19-470d-9494-9b76944e0241";
    private static final String REF = "GFS-26-EUR00001";

    private final PayopFakes.Invoices invoices = new PayopFakes.Invoices();
    private final PayopClient client = mock(PayopClient.class);
    private final OrderRepository orders = mock(OrderRepository.class);
    private final OrderService orderService = mock(OrderService.class);
    private final PaymentRepository payments = mock(PaymentRepository.class);
    private final com.globalfutservice.payments.ManualPaymentClaimRepository claims =
            mock(com.globalfutservice.payments.ManualPaymentClaimRepository.class);
    private final WebhookLedger ledger = mock(WebhookLedger.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final List<PaymentEntity> paymentRows = new ArrayList<>();
    private final Set<String> processedEvents = new HashSet<>();
    private final List<String> ledgerPayloads = new ArrayList<>();
    private PayopCallbackService callbacks;
    private OrderEntity order;
    private PayopInvoiceEntity attempt;

    @BeforeEach
    void setUp() {
        order = PayopFakes.order(7, REF, Currency.EUR, OrderStatus.AWAITING_PAYMENT);
        when(orders.findById(7L)).thenReturn(Optional.of(order));
        attempt = invoices.open(7, INVOICE, 381, Currency.EUR, 9000, 407, "94.07", NOW.instant().minusSeconds(600));

        when(payments.save(any(PaymentEntity.class))).thenAnswer(inv -> {
            paymentRows.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(payments.findByProviderAndProviderPaymentId(eq("PAYOP"), anyString())).thenAnswer(inv ->
                paymentRows.stream().filter(p -> inv.getArgument(1).equals(p.getProviderPaymentId())).findFirst());
        when(payments.findByOrderId(anyLong())).thenAnswer(inv ->
                paymentRows.stream().filter(p -> p.getOrderId().equals(inv.getArgument(0))).toList());
        // markPaid's effect, as far as these tests need it.
        doAnswer(inv -> {
            OrderEntity o = inv.getArgument(0);
            if (o.getStatus() == OrderStatus.AWAITING_PAYMENT) {
                ReflectionTestUtils.setField(o, "status", OrderStatus.PAID);
            }
            return null;
        }).when(orderService).markPaid(any(), anyString());
        // The ledger: a repeat of a processed event comes back empty.
        when(ledger.recordOrRetry(eq("PAYOP"), anyString(), anyString(), anyString())).thenAnswer(inv -> {
            ledgerPayloads.add(inv.getArgument(3));
            return processedEvents.contains((String) inv.getArgument(1)) ? Optional.empty()
                    : Optional.of((long) inv.getArgument(1).hashCode());
        });
        doAnswer(inv -> null).when(ledger).markProcessed(anyLong());

        AppProperties props = mock(AppProperties.class);
        when(props.publicUrl()).thenReturn("https://globalfutservices.com");
        callbacks = new PayopCallbackService(invoices.repo, client, orders, orderService, payments, claims, ledger,
                notifications, props, MAPPER, PayopFakes.noTransactions(), NOW);
    }

    /** Payop's documented IPN, with our attempt in the metadata and a payer field we must not keep. */
    private static byte[] ipn(String invoiceId, String txid, int state, String attemptId) {
        return """
                {
                 "invoice": {
                   "id": "%s",
                   "status": %d,
                   "txid": "%s",
                   "metadata": {"attemptId": %s, "orderRef": "%s"}
                 },
                 "transaction": {
                   "id": "%s",
                   "state": %d,
                   "order": {"id": "%s"},
                   "error": {"message": "", "code": ""}
                 },
                 "payer": {"email": "buyer@example.com", "name": "Buyer Name"}
                }
                """.formatted(invoiceId, state == 2 ? 1 : 5, txid, attemptId == null ? "null" : "\"" + attemptId + "\"",
                REF, txid, state, REF).getBytes(StandardCharsets.UTF_8);
    }

    private PayopCallbackService.Outcome deliver(byte[] body) {
        PayopCallbackService.Ipn parsed = callbacks.parse(body).orElseThrow();
        PayopCallbackService.Outcome outcome = callbacks.handle(parsed);
        processedEvents.add(parsed.txid() + ":" + parsed.state() + ":" + parsed.invoiceStatus());
        return outcome;
    }

    private void payopSays(int state, String amount, String currency, String orderId, String error) {
        when(client.transaction(TXID)).thenReturn(new PayopClient.Transaction(TXID, state, error, orderId,
                amount == null ? null : new BigDecimal(amount), currency, null));
    }

    private PaymentAlert alert() {
        ArgumentCaptor<PaymentAlert> captor = ArgumentCaptor.forClass(PaymentAlert.class);
        verify(notifications).paymentAlert(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("valid: Payop's API confirms the exact amount, currency and order, and the order is paid once")
    void valid() throws Exception {
        payopSays(2, "94.07", "EUR", REF, null);

        assertThat(deliver(ipn(INVOICE, TXID, 2, attempt.getAttemptId().toString())))
                .isEqualTo(PayopCallbackService.Outcome.PAID);

        assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.PAID);
        assertThat(attempt.getTxid()).isEqualTo(TXID);
        assertThat(paymentRows).singleElement().satisfies(p -> {
            assertThat(p.getProviderPaymentId()).isEqualTo(TXID);
            assertThat(p.getProviderOrderId()).isEqualTo(INVOICE);
            assertThat(p.getAmountMinor()).isEqualTo(9407);
            assertThat(p.getStatus()).isEqualTo(PaymentStatus.CAPTURED);
            assertThat(ReflectionTestUtils.getField(p, "provider")).isEqualTo("PAYOP");
        });
        assertThat(order.getTotalMinor()).as("what was actually charged").isEqualTo(9407);
        assertThat(order.getPayopInvoiceId()).isEqualTo(attempt.getId());
        JsonNode fee = MAPPER.readTree(order.getPaymentFee());
        assertThat(fee.path("label").asText()).isEqualTo("Payment processing fee");
        assertThat(fee.path("methodId").asLong()).isEqualTo(381);
        assertThat(fee.path("fixedEur").asText()).isEqualTo("0.30");
        assertThat(fee.path("percent").asText()).isEqualTo("4.0");
        assertThat(fee.path("netMinor").asLong()).isEqualTo(9000);
        assertThat(fee.path("feeMinor").asLong()).isEqualTo(407);
        assertThat(fee.path("totalMinor").asLong()).isEqualTo(9407);
        verify(orderService, times(1)).markPaid(order, "Payop " + TXID);
        verify(notifications, never()).paymentAlert(any());
    }

    @Test
    @DisplayName("an exact repeat of an IPN is logged once and ignored: Payop is not asked again")
    void duplicateIpn() {
        payopSays(2, "94.07", "EUR", REF, null);
        deliver(ipn(INVOICE, TXID, 2, null));
        assertThat(deliver(ipn(INVOICE, TXID, 2, null))).isEqualTo(PayopCallbackService.Outcome.IGNORED);
        verify(client, times(1)).transaction(TXID);
        verify(orderService, times(1)).markPaid(any(), anyString());
        assertThat(paymentRows).hasSize(1);
    }

    @Test
    @DisplayName("the ledger keeps identifiers and states, never the payer")
    void ledgerKeepsNoPayer() {
        payopSays(2, "94.07", "EUR", REF, null);
        deliver(ipn(INVOICE, TXID, 2, null));
        assertThat(ledgerPayloads).singleElement().satisfies(p -> assertThat(p)
                .contains(INVOICE).contains(TXID).doesNotContain("buyer@example.com").doesNotContain("Buyer Name"));
    }

    @Test
    @DisplayName("wrong amount: not paid, held for review, staff alerted")
    void wrongAmount() {
        payopSays(2, "1.00", "EUR", REF, null);
        assertThat(deliver(ipn(INVOICE, TXID, 2, null))).isEqualTo(PayopCallbackService.Outcome.REVIEW);
        assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.REVIEW);
        assertThat(attempt.getReviewReason()).isEqualTo("AMOUNT_MISMATCH");
        assertThat(alert().detail()).contains("1.00 EUR").contains("94.07 EUR");
        verify(orderService, never()).markPaid(any(), anyString());
        assertThat(paymentRows).isEmpty();
    }

    @Test
    @DisplayName("wrong currency: not paid, held for review")
    void wrongCurrency() {
        payopSays(2, "94.07", "USD", REF, null);
        assertThat(deliver(ipn(INVOICE, TXID, 2, null))).isEqualTo(PayopCallbackService.Outcome.REVIEW);
        assertThat(attempt.getReviewReason()).isEqualTo("CURRENCY_MISMATCH");
        verify(orderService, never()).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("Payop's answer without the invoice amount (as in its docs' example): never paid on a guess")
    void noProductAmount() {
        payopSays(2, null, null, REF, null);
        assertThat(deliver(ipn(INVOICE, TXID, 2, null))).isEqualTo(PayopCallbackService.Outcome.REVIEW);
        assertThat(attempt.getReviewReason()).isEqualTo("UNCONFIRMED_AMOUNT");
        verify(orderService, never()).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("a transaction for another order reference: not paid")
    void wrongOrder() {
        payopSays(2, "94.07", "EUR", "GFS-26-OTHER001", null);
        assertThat(deliver(ipn(INVOICE, TXID, 2, null))).isEqualTo(PayopCallbackService.Outcome.REVIEW);
        assertThat(attempt.getReviewReason()).isEqualTo("ORDER_MISMATCH");
        verify(orderService, never()).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("an IPN naming another attempt in its metadata: not paid, and Payop not even asked")
    void wrongAttempt() {
        assertThat(deliver(ipn(INVOICE, TXID, 2, "00000000-0000-0000-0000-000000000000")))
                .isEqualTo(PayopCallbackService.Outcome.REVIEW);
        assertThat(attempt.getReviewReason()).isEqualTo("METADATA_MISMATCH");
        verify(client, never()).transaction(anyString());
    }

    @Test
    @DisplayName("an unknown invoice: nothing changes, staff are told")
    void unknownOrder() {
        assertThat(deliver(ipn("99999999-0000-4000-8000-000000000000", TXID, 2, null)))
                .isEqualTo(PayopCallbackService.Outcome.IGNORED);
        assertThat(alert().code()).isEqualTo("UNKNOWN_INVOICE");
        verify(client, never()).transaction(anyString());
        verify(orderService, never()).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("the IPN says accepted, Payop's API says failed: the API decides, nothing is paid")
    void apiSaysFailed() {
        payopSays(5, "94.07", "EUR", REF, "timeout");
        assertThat(deliver(ipn(INVOICE, TXID, 2, null))).isEqualTo(PayopCallbackService.Outcome.FAILED);
        assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.FAILED);
        assertThat(attempt.getReviewReason()).isEqualTo("TIMEOUT");
        verify(orderService, never()).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("the IPN says failed, a later one accepted: the update is applied (failed -> accepted)")
    void failedThenAccepted() {
        payopSays(5, "94.07", "EUR", REF, "We are unable to process your payment due to security reasons.");
        assertThat(deliver(ipn(INVOICE, TXID, 5, null))).isEqualTo(PayopCallbackService.Outcome.FAILED);
        assertThat(attempt.getReviewReason()).isEqualTo("REJECTED");

        payopSays(2, "94.07", "EUR", REF, null);
        assertThat(deliver(ipn(INVOICE, TXID, 2, null))).isEqualTo(PayopCallbackService.Outcome.PAID);
        verify(orderService, times(1)).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("any other failure message is a plain failure; the message itself is not kept")
    void otherFailure() {
        payopSays(5, "94.07", "EUR", REF, "Card holder name: John Smith declined");
        deliver(ipn(INVOICE, TXID, 5, null));
        assertThat(attempt.getReviewReason()).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("pending or pre-approved is not final: nothing changes")
    void notFinal() {
        payopSays(9, "94.07", "EUR", REF, null);
        assertThat(callbacks.confirm(attempt.getId(), TXID)).isEqualTo(PayopCallbackService.Outcome.PENDING);
        assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.OPEN);
        verify(orderService, never()).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("a second payment for an order already paid: not applied, 'duplicate payment, refund needed'")
    void secondPayment() {
        payopSays(2, "94.07", "EUR", REF, null);
        deliver(ipn(INVOICE, TXID, 2, null));

        // The customer had also opened a second invoice (another method) and paid that too.
        String invoice2 = "e0000000-ba2d-456f-910e-4d7fdfd338dd";
        String txid2 = "f0000000-be19-470d-9494-9b76944e0241";
        PayopInvoiceEntity second = invoices.open(7, invoice2, 700001, Currency.EUR, 9000, 485, "94.85",
                NOW.instant().minusSeconds(300));
        when(client.transaction(txid2)).thenReturn(new PayopClient.Transaction(txid2, 2, null, REF,
                new BigDecimal("94.85"), "EUR", null));

        assertThat(deliver(ipn(invoice2, txid2, 2, null))).isEqualTo(PayopCallbackService.Outcome.DUPLICATE);
        assertThat(second.getStatus()).isEqualTo(PayopInvoiceEntity.Status.DUPLICATE);
        assertThat(second.getReviewReason()).isEqualTo("DUPLICATE_PAYMENT");
        PaymentAlert a = alert();
        assertThat(a.headline()).isEqualTo("Duplicate payment, refund needed");
        assertThat(a.publicRef()).isEqualTo(REF);
        assertThat(paymentRows).hasSize(1);
        assertThat(order.getTotalMinor()).isEqualTo(9407);
        verify(orderService, times(1)).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("a payment on an invoice the customer moved away from still counts once: applied if the order is "
            + "unpaid")
    void supersededInvoicePaidFirst() {
        // The customer opened INVOICE, switched to another method, then paid the first invoice anyway.
        attempt.expired(PayopInvoiceEntity.REPLACED, NOW.instant().minusSeconds(300));
        invoices.open(7, "e0000000-ba2d-456f-910e-4d7fdfd338dd", 700001, Currency.EUR, 9000, 485, "94.85",
                NOW.instant().minusSeconds(300));
        payopSays(2, "94.07", "EUR", REF, null);

        assertThat(deliver(ipn(INVOICE, TXID, 2, null))).isEqualTo(PayopCallbackService.Outcome.PAID);
        assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.PAID);
        assertThat(order.getTotalMinor()).isEqualTo(9407);
        verify(orderService, times(1)).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("a payment on a superseded invoice after the order was paid with the new one: 'duplicate payment, "
            + "refund needed'")
    void supersededInvoicePaidSecond() {
        attempt.expired(PayopInvoiceEntity.REPLACED, NOW.instant().minusSeconds(300));
        String invoice2 = "e0000000-ba2d-456f-910e-4d7fdfd338dd";
        String txid2 = "f0000000-be19-470d-9494-9b76944e0241";
        invoices.open(7, invoice2, 700001, Currency.EUR, 9000, 485, "94.85", NOW.instant().minusSeconds(300));
        when(client.transaction(txid2)).thenReturn(new PayopClient.Transaction(txid2, 2, null, REF,
                new BigDecimal("94.85"), "EUR", null));
        assertThat(deliver(ipn(invoice2, txid2, 2, null))).isEqualTo(PayopCallbackService.Outcome.PAID);

        payopSays(2, "94.07", "EUR", REF, null);
        assertThat(deliver(ipn(INVOICE, TXID, 2, null))).isEqualTo(PayopCallbackService.Outcome.DUPLICATE);
        assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.DUPLICATE);
        assertThat(alert().headline()).isEqualTo("Duplicate payment, refund needed");
        assertThat(paymentRows).hasSize(1);
        verify(orderService, times(1)).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("a payment for an abandoned order: not applied, held for a person to decide")
    void closedOrder() {
        ReflectionTestUtils.setField(order, "status", OrderStatus.ABANDONED);
        payopSays(2, "94.07", "EUR", REF, null);
        assertThat(deliver(ipn(INVOICE, TXID, 2, null))).isEqualTo(PayopCallbackService.Outcome.REVIEW);
        assertThat(attempt.getReviewReason()).isEqualTo("ORDER_ABANDONED");
        assertThat(alert().code()).isEqualTo("PAID_CLOSED_ORDER");
        verify(orderService, never()).markPaid(any(), anyString());
    }

    @Test
    @DisplayName("Payop cannot be asked: nothing changes, the IPN stays unprocessed and is handled when Payop retries")
    void payopUnreachable() {
        when(client.transaction(TXID)).thenThrow(new PayopClient.PayopException("get transaction",
                PayopClient.ErrorCode.UNAVAILABLE, 0, List.of()));
        PayopCallbackService.Ipn parsed = callbacks.parse(ipn(INVOICE, TXID, 2, null)).orElseThrow();
        assertThatThrownBy(() -> callbacks.handle(parsed)).isInstanceOfSatisfying(PayopClient.PayopException.class,
                e -> assertThat(e.isTransient()).isTrue());
        verify(ledger).markFailed(anyLong(), eq("UNAVAILABLE"));
        assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.OPEN);

        org.mockito.Mockito.reset(client);
        payopSays(2, "94.07", "EUR", REF, null);
        assertThat(callbacks.handle(parsed)).isEqualTo(PayopCallbackService.Outcome.PAID);
    }

    @Test
    @DisplayName("an IPN from the wrong address is kept for staff only when it names one of our invoices")
    void rejectedKept() {
        callbacks.recordRejected(callbacks.parse(ipn(INVOICE, TXID, 2, null)).orElseThrow(), "198.51.100.7");
        callbacks.recordRejected(callbacks.parse(ipn("99999999-0000-4000-8000-000000000000", TXID, 2, null))
                .orElseThrow(), "198.51.100.7");
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(ledger, times(1)).record(eq("PAYOP"), anyString(), eq(PayopCallbackService.EVENT_REJECTED),
                payload.capture());
        assertThat(payload.getValue()).contains("198.51.100.7").contains(INVOICE).doesNotContain("buyer@example.com");
        verify(client, never()).transaction(anyString());
    }

    @Test
    @DisplayName("an admin accepting a payment in review applies it once")
    void acceptByHand() {
        payopSays(2, null, null, REF, null);
        deliver(ipn(INVOICE, TXID, 2, null));
        assertThat(attempt.getStatus()).isEqualTo(PayopInvoiceEntity.Status.REVIEW);

        assertThat(callbacks.acceptByHand(attempt.getId(), TXID, 3L)).isEqualTo(PayopCallbackService.Outcome.PAID);
        assertThat(callbacks.acceptByHand(attempt.getId(), TXID, 3L))
                .isEqualTo(PayopCallbackService.Outcome.ALREADY_APPLIED);
        verify(orderService, times(1)).markPaid(order, "Payop " + TXID + ", accepted by staff");
        assertThatThrownBy(() -> callbacks.acceptByHand(attempt.getId(), "../x", 3L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("bodies that are not a Payop IPN are not read further")
    void unreadable() {
        assertThat(callbacks.parse("not json".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(callbacks.parse("{}".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(callbacks.parse(ipn("../../etc", TXID, 2, null))).isEmpty();
    }
}
