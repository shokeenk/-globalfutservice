package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.globalfutservice.config.AppProperties;
import com.globalfutservice.domain.orders.OrderStatus;
import com.globalfutservice.domain.payments.ClaimStatus;
import com.globalfutservice.domain.payments.PaymentStatus;
import com.globalfutservice.notify.NotificationService;
import com.globalfutservice.notify.PaymentAlert;
import com.globalfutservice.orders.OrderEntity;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import com.globalfutservice.payments.ManualPaymentClaimRepository;
import com.globalfutservice.payments.PaymentEntity;
import com.globalfutservice.payments.PaymentRepository;
import com.globalfutservice.payments.WebhookLedger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Payop telling us a payment reached its final state, and what we do about it.
 *
 * <p>Payop signs nothing; its IPNs are recognised by the address they come from, which the
 * controller checks first. That is a filter, not proof. Whatever the IPN says, the
 * transaction is then fetched from Payop's API with our token, and an order is paid only
 * when Payop itself says the transaction was accepted (state 2) for exactly the amount and
 * currency of our invoice, for this order, and for this attempt. Anything less goes to a
 * person.
 *
 * <p>Idempotent: every IPN is logged in the webhook ledger, an exact repeat is ignored, a
 * changed status is applied, and a transaction is applied at most once. An order already
 * paid never takes a second payment: that one is held as a duplicate for a refund.
 */
@Service
public class PayopCallbackService {

    private static final Logger log = LoggerFactory.getLogger(PayopCallbackService.class);

    public static final String PROVIDER = "PAYOP";
    /** Ledger type of an IPN that passed the address check. */
    static final String EVENT_IPN = "CHECKOUT_IPN";
    /** Ledger type of one that did not: kept so staff can ask Payop about it. */
    public static final String EVENT_REJECTED = "REJECTED_SOURCE";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    /** What became of a callback or a check. */
    public enum Outcome { PAID, ALREADY_APPLIED, DUPLICATE, REVIEW, FAILED, PENDING, IGNORED, UNAVAILABLE }

    /** The parts of an IPN we use; the rest, payer details included, is never kept. */
    public record Ipn(String invoiceId, String txid, int state, int invoiceStatus, String orderId, String attemptId) {
    }

    private final PayopInvoiceRepository invoices;
    private final PayopClient client;
    private final OrderRepository orders;
    private final OrderService orderService;
    private final PaymentRepository payments;
    private final ManualPaymentClaimRepository claims;
    private final WebhookLedger ledger;
    private final NotificationService notifications;
    private final AppProperties props;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final Clock clock;

    public PayopCallbackService(PayopInvoiceRepository invoices, PayopClient client, OrderRepository orders,
                                OrderService orderService, PaymentRepository payments,
                                ManualPaymentClaimRepository claims, WebhookLedger ledger,
                                NotificationService notifications, AppProperties props, ObjectMapper mapper,
                                PlatformTransactionManager transactions, Clock clock) {
        this.invoices = invoices;
        this.client = client;
        this.orders = orders;
        this.orderService = orderService;
        this.payments = payments;
        this.claims = claims;
        this.ledger = ledger;
        this.notifications = notifications;
        this.props = props;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    /** Reads an IPN body; empty when it is not one we can act on. */
    public Optional<Ipn> parse(byte[] body) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            return Optional.empty();
        }
        if (root == null) {
            return Optional.empty();
        }
        String invoiceId = text(root.path("invoice").path("id"));
        String txid = text(root.path("invoice").path("txid"));
        if (txid == null) {
            txid = text(root.path("transaction").path("id"));
        }
        if (invoiceId == null || txid == null || !ID.matcher(invoiceId).matches() || !ID.matcher(txid).matches()) {
            return Optional.empty();
        }
        return Optional.of(new Ipn(invoiceId, txid, root.path("transaction").path("state").asInt(-1),
                root.path("invoice").path("status").asInt(-1), text(root.path("transaction").path("order").path("id")),
                text(root.path("invoice").path("metadata").path("attemptId"))));
    }

    /**
     * An IPN that came from one of Payop's addresses. Throws a transient
     * {@link PayopClient.PayopException} when Payop could not be asked to confirm it, so the
     * caller answers with an error and Payop sends it again.
     */
    public Outcome handle(Ipn ipn) {
        Optional<Long> row = ledger.recordOrRetry(PROVIDER, ipn.txid() + ":" + ipn.state() + ":" + ipn.invoiceStatus(),
                EVENT_IPN, reduced(ipn, null));
        if (row.isEmpty()) {
            log.info("Payop IPN for invoice {} (state {}) already handled", ipn.invoiceId(), ipn.state());
            return Outcome.IGNORED;
        }
        Outcome outcome;
        try {
            outcome = process(ipn);
        } catch (PayopClient.PayopException e) {
            ledger.markFailed(row.get(), e.code().name());
            throw e;
        } catch (RuntimeException e) {
            ledger.markFailed(row.get(), e.getClass().getSimpleName());
            throw e;
        }
        ledger.markProcessed(row.get());
        log.info("Payop IPN for invoice {} (state {}): {}", ipn.invoiceId(), ipn.state(), outcome);
        return outcome;
    }

    /** An IPN from an address not on Payop's list: refused, but kept for staff to check with Payop. */
    public void recordRejected(Ipn ipn, String sourceIp) {
        if (invoices.findByInvoiceId(ipn.invoiceId()).isEmpty()) {
            return; // not one of our invoices: nothing for staff to check, and nothing kept
        }
        ledger.record(PROVIDER, "rejected:" + ipn.txid() + ":" + ipn.state() + ":" + ipn.invoiceStatus(),
                EVENT_REJECTED, reduced(ipn, sourceIp));
    }

    private Outcome process(Ipn ipn) {
        Optional<PayopInvoiceEntity> attempt = invoices.findByInvoiceId(ipn.invoiceId());
        if (attempt.isEmpty()) {
            log.warn("Payop IPN for invoice {}, which is not one of ours", ipn.invoiceId());
            alert(null, "Payop reported a payment we have no invoice for",
                    "Payop sent a notification for invoice " + ipn.invoiceId() + " (transaction " + ipn.txid()
                            + "), which this site did not create. Nothing was changed. Look it up in Payop's dashboard.",
                    "UNKNOWN_INVOICE");
            return Outcome.IGNORED;
        }
        PayopInvoiceEntity a = attempt.get();
        if (ipn.attemptId() != null && !ipn.attemptId().equals(a.getAttemptId().toString())) {
            return review(a, ipn.txid(), "METADATA_MISMATCH",
                    "Payop's notification for invoice " + a.getInvoiceId() + " names a different payment attempt "
                            + "than the one this site made. The order was not marked paid.");
        }
        return confirm(a.getId(), ipn.txid());
    }

    /**
     * Asks Payop about {@code txid} and acts on its answer. Used for every IPN, and by staff
     * to check a payment whose IPN was refused or never came.
     */
    public Outcome confirm(Long attemptId, String txid) {
        PayopInvoiceEntity a = invoices.findById(attemptId).orElseThrow();
        PayopClient.Transaction t = client.transaction(txid);
        return switch (t.state()) {
            case 2 -> accepted(a, txid, t);
            case 3, 5, 15 -> failed(a, txid, t);
            default -> {
                // New, pending or pre-approved: not final, and pre-approved may yet fail.
                log.info("Payop transaction for invoice {} is not final yet (state {})", a.getInvoiceId(), t.state());
                yield Outcome.PENDING;
            }
        };
    }

    private Outcome accepted(PayopInvoiceEntity a, String txid, PayopClient.Transaction t) {
        OrderEntity order = orders.findById(a.getOrderId()).orElseThrow();
        String expected = a.getAmountSent() + " " + a.getCurrency();
        if (t.productAmount() == null || t.productCurrency() == null) {
            return review(a, txid, "UNCONFIRMED_AMOUNT", "Payop says the payment for invoice " + a.getInvoiceId()
                    + " was accepted, but its answer did not state the invoice amount, so it could not be checked "
                    + "against " + expected + ". The order was not marked paid. Check the payment in Payop's "
                    + "dashboard, then accept it in the admin console if it is right.");
        }
        String reported = t.productAmount().toPlainString() + " " + t.productCurrency();
        if (t.productAmount().compareTo(new BigDecimal(a.getAmountSent())) != 0) {
            return review(a, txid, "AMOUNT_MISMATCH", "Payop reports " + reported + " for invoice " + a.getInvoiceId()
                    + "; this site asked for " + expected + ". The order was not marked paid.");
        }
        if (!t.productCurrency().trim().equalsIgnoreCase(a.getCurrency().name())) {
            return review(a, txid, "CURRENCY_MISMATCH", "Payop reports " + reported + " for invoice "
                    + a.getInvoiceId() + "; this site asked for " + expected + ". The order was not marked paid.");
        }
        if (t.orderId() == null || !t.orderId().trim().equalsIgnoreCase(order.getPublicRef())) {
            return review(a, txid, "ORDER_MISMATCH", "Payop's transaction for invoice " + a.getInvoiceId()
                    + " is for a different order reference. The order was not marked paid.");
        }
        if (t.metadataAttemptId() != null && !t.metadataAttemptId().equals(a.getAttemptId().toString())) {
            return review(a, txid, "METADATA_MISMATCH", "Payop's transaction for invoice " + a.getInvoiceId()
                    + " names a different payment attempt. The order was not marked paid.");
        }
        return apply(a.getId(), txid, null);
    }

    /**
     * Payop says the invoice is paid but names no transaction, so there is nothing to confirm
     * the amount, currency and order with. Never paid on the invoice status alone: a person
     * checks it in Payop's dashboard and accepts it.
     */
    public Outcome reviewPaidWithoutTransaction(Long attemptId) {
        PayopInvoiceEntity a = invoices.findById(attemptId).orElseThrow();
        return review(a, null, "PAID_NO_TRANSACTION", "Payop reports invoice " + a.getInvoiceId() + " as paid but "
                + "did not name its transaction, so the payment could not be checked against " + a.getAmountSent()
                + " " + a.getCurrency() + ". The order was not marked paid. Check the payment in Payop's dashboard, "
                + "then accept it in the admin console if it is right.");
    }

    /**
     * An admin accepting a payment in review, having checked it in Payop's dashboard: for
     * when Payop's API could not confirm it (no invoice amount in its answer, say). Applied
     * exactly like a confirmed one, so it is still applied at most once, and an order already
     * paid still turns it into a duplicate.
     */
    public Outcome acceptByHand(Long attemptId, String txid, Long adminId) {
        if (txid == null || !ID.matcher(txid).matches()) {
            throw new IllegalArgumentException("not a Payop transaction ID");
        }
        return apply(attemptId, txid, adminId);
    }

    /**
     * Applies a confirmed payment, at most once. {@code acceptedBy} is the operator who
     * accepted it by hand after checking Payop's dashboard, or null when Payop's API
     * confirmed it.
     */
    Outcome apply(Long attemptId, String txid, Long acceptedBy) {
        return tx.execute(status -> {
            // Locked: an IPN and the reconciliation job confirming the same payment take turns.
            PayopInvoiceEntity a = invoices.lockById(attemptId).orElseThrow();
            if (a.getStatus() == PayopInvoiceEntity.Status.PAID) {
                return Outcome.ALREADY_APPLIED;
            }
            if (payments.findByProviderAndProviderPaymentId(PROVIDER, txid).isPresent()) {
                return Outcome.ALREADY_APPLIED;
            }
            OrderEntity order = orders.findById(a.getOrderId()).orElseThrow();
            if (order.getStatus() != OrderStatus.AWAITING_PAYMENT) {
                if (paidOrLater(order.getStatus()) && !paidOtherwise(order.getId(), txid)) {
                    /*
                     * Moved on by hand, with no payment recorded: most likely staff moved it
                     * because this very payment's IPN never got through. Not a duplicate to
                     * refund on a guess, and not recorded on one either -- the customer may
                     * have paid some way nothing here records. A person says which; accepting
                     * it records the payment against the order as it stands.
                     */
                    if (acceptedBy != null) {
                        record(a, order, txid);
                        log.info("Payop payment for invoice {} recorded against order {}, moved on by hand before it "
                                + "was confirmed", a.getInvoiceId(), order.getPublicRef());
                        return Outcome.PAID;
                    }
                    a.review(txid, "ORDER_MOVED_BY_HAND", clock.instant());
                    invoices.save(a);
                    alert(order.getPublicRef(), "Payop payment for an order moved on by hand",
                            "Payop confirms a payment of " + a.getAmountSent() + " " + a.getCurrency() + " (invoice "
                                    + a.getInvoiceId() + ", transaction " + txid + ") for an order that was moved to "
                                    + order.getStatus().name() + " by hand, with no payment recorded. If this is the "
                                    + "payment the order was moved on for, accept it in Payop payments to record it. "
                                    + "If the customer also paid another way, refund it in Payop's dashboard.",
                            "ORDER_MOVED_BY_HAND");
                    return Outcome.REVIEW;
                }
                if (paidOrLater(order.getStatus())) {
                    a.duplicate(txid, "DUPLICATE_PAYMENT", clock.instant());
                    invoices.save(a);
                    log.warn("Payop payment for invoice {} arrived for order {}, already paid: held for a refund",
                            a.getInvoiceId(), order.getPublicRef());
                    alert(order.getPublicRef(), "Duplicate payment, refund needed",
                            "A second payment of " + a.getAmountSent() + " " + a.getCurrency() + " through Payop "
                                    + "(invoice " + a.getInvoiceId() + ", transaction " + txid + ") arrived for an "
                                    + "order that was already paid. It was not applied. Refund it in Payop's dashboard.",
                            "DUPLICATE_PAYMENT");
                    return Outcome.DUPLICATE;
                }
                a.review(txid, "ORDER_" + order.getStatus().name(), clock.instant());
                invoices.save(a);
                alert(order.getPublicRef(), "Payop payment for a closed order",
                        "A payment of " + a.getAmountSent() + " " + a.getCurrency() + " through Payop (invoice "
                                + a.getInvoiceId() + ") arrived for an order that is " + order.getStatus().name()
                                + ". It was not applied. Decide whether to reopen the order or refund the payment.",
                        "PAID_CLOSED_ORDER");
                return Outcome.REVIEW;
            }
            record(a, order, txid);
            orderService.markPaid(order, "Payop " + txid + (acceptedBy == null ? "" : ", accepted by staff"));
            log.info("Order {} paid through Payop: invoice {}", order.getPublicRef(), a.getInvoiceId());
            return Outcome.PAID;
        });
    }

    /** The payment, the invoice paid, and the fee on the order. Inside {@link #apply}'s transaction. */
    private void record(PayopInvoiceEntity a, OrderEntity order, String txid) {
        PaymentEntity payment = new PaymentEntity(order.getId(), PROVIDER, a.getInvoiceId(), a.getTotalMinor(),
                a.getCurrency());
        payment.setProviderPaymentId(txid);
        payment.setMethod(a.getMethodName());
        payment.setStatus(PaymentStatus.CAPTURED);
        payments.save(payment);
        a.paid(txid, clock.instant());
        invoices.save(a);
        order.recordPayopPayment(a.getId(), a.getTotalMinor(), feeSnapshot(a));
    }

    /**
     * Whether the order is paid by something on record other than this transaction: a captured
     * payment (card, or another Payop transaction) or a payment claim staff verified. Only then
     * is a Payop payment for an order past AWAITING_PAYMENT a duplicate to refund.
     */
    private boolean paidOtherwise(Long orderId, String txid) {
        boolean captured = payments.findByOrderId(orderId).stream()
                .anyMatch(p -> p.getStatus() == PaymentStatus.CAPTURED && !txid.equals(p.getProviderPaymentId()));
        return captured || claims.findFirstByOrderIdAndStatusOrderByReviewedAtDesc(orderId, ClaimStatus.VERIFIED)
                .isPresent();
    }

    private Outcome failed(PayopInvoiceEntity a, String txid, PayopClient.Transaction t) {
        String reason = t.state() == 15 || "timeout".equalsIgnoreCase(t.error() == null ? "" : t.error().trim())
                ? "TIMEOUT"
                : t.error() != null && t.error().toLowerCase(Locale.ROOT).contains("security reason") ? "REJECTED"
                : "FAILED";
        return tx.execute(status -> {
            PayopInvoiceEntity fresh = invoices.lockById(a.getId()).orElseThrow();
            switch (fresh.getStatus()) {
                case PAID, DUPLICATE, REVIEW -> {
                    // A failed attempt after a settled one changes nothing about the money.
                    return Outcome.IGNORED;
                }
                default -> {
                    fresh.failed(txid, reason, clock.instant());
                    invoices.save(fresh);
                    return Outcome.FAILED;
                }
            }
        });
    }

    private Outcome review(PayopInvoiceEntity a, String txid, String code, String detail) {
        Boolean moved = tx.execute(status -> invoices.lockById(a.getId()).map(fresh -> {
            if (fresh.getStatus() == PayopInvoiceEntity.Status.PAID) {
                return false; // settled meanwhile, by the IPN or the job: nothing left to review
            }
            fresh.review(txid, code, clock.instant());
            invoices.save(fresh);
            return true;
        }).orElse(false));
        if (!Boolean.TRUE.equals(moved)) {
            return Outcome.ALREADY_APPLIED;
        }
        String ref = orders.findById(a.getOrderId()).map(OrderEntity::getPublicRef).orElse(null);
        log.warn("Payop payment for invoice {} on order {} needs review: {}", a.getInvoiceId(), ref, code);
        alert(ref, "Payop payment needs checking", detail, code);
        return Outcome.REVIEW;
    }

    private void alert(String publicRef, String headline, String detail, String code) {
        notifications.paymentAlert(new PaymentAlert(publicRef, headline, detail, code,
                props.publicUrl() + "/admin/payop"));
    }

    private static boolean paidOrLater(OrderStatus status) {
        return status != OrderStatus.DRAFT && status != OrderStatus.AWAITING_PAYMENT
                && status != OrderStatus.ABANDONED;
    }

    /** What the order keeps about the fee it was charged, exactly as charged. */
    String feeSnapshot(PayopInvoiceEntity a) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("provider", PROVIDER);
        s.put("label", PayopCheckoutService.FEE_LABEL);
        s.put("methodId", a.getMethodId());
        s.put("methodName", a.getMethodName());
        s.put("methodVersion", a.getMethodVersion());
        s.put("fixedEur", a.getFixedEur().toPlainString());
        s.put("percent", a.getPercent().toPlainString());
        s.put("fxRate", a.getFxRate().toPlainString());
        s.put("fxSource", a.getFxSource());
        s.put("fxDate", a.getFxDate().toString());
        s.put("currency", a.getCurrency().name());
        s.put("netMinor", a.getNetMinor());
        s.put("feeMinor", a.getFeeMinor());
        s.put("totalMinor", a.getTotalMinor());
        s.put("invoiceId", a.getInvoiceId());
        try {
            return mapper.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not record the Payop fee", e);
        }
    }

    /** The ledger's copy of an IPN: identifiers and states only, no payer details. */
    private String reduced(Ipn ipn, String sourceIp) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("invoiceId", ipn.invoiceId());
        s.put("txid", ipn.txid());
        s.put("state", ipn.state());
        s.put("invoiceStatus", ipn.invoiceStatus());
        s.put("orderId", ipn.orderId());
        s.put("attemptId", ipn.attemptId());
        if (sourceIp != null) {
            s.put("sourceIp", sourceIp);
        }
        try {
            return mapper.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("could not record the Payop IPN", e);
        }
    }

    private static String text(JsonNode node) {
        return node.isValueNode() && !node.isNull() && !node.asText().isBlank() ? node.asText().trim() : null;
    }
}
