package com.globalfutservice.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.globalfutservice.domain.payments.PaymentStatus;
import com.globalfutservice.orders.OrderRepository;
import com.globalfutservice.orders.OrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Applies one verified gateway event to the ledger, in its own transaction.
 *
 * <p>A separate bean from the orchestrator for the same reason {@link WebhookLedger}
 * is: {@code @Transactional} is proxy-based, so a call between two methods of the
 * same bean never starts a transaction. Splitting the "record the delivery",
 * "apply the effect" and "mark the outcome" steps into distinct beans is what makes
 * their transaction boundaries real rather than decorative — and it is why a failure
 * while applying still leaves an audit row describing it.
 */
@Component
public class PaymentApplier {

    private static final Logger log = LoggerFactory.getLogger(PaymentApplier.class);
    private static final String PROVIDER = "RAZORPAY";

    private final PaymentRepository payments;
    private final OrderRepository orders;
    private final OrderService orderService;

    public PaymentApplier(PaymentRepository payments, OrderRepository orders,
                          OrderService orderService) {
        this.payments = payments;
        this.orders = orders;
        this.orderService = orderService;
    }

    @Transactional
    public void apply(String eventType, JsonNode root) {
        switch (eventType) {
            case "payment.captured" -> applyCapture(root);
            case "payment.failed" -> applyFailure(root);
            case "refund.processed" -> applyRefund(root);
            default -> log.debug("Webhook event {} not handled", eventType);
        }
    }

    private void applyCapture(JsonNode root) {
        JsonNode entity = root.path("payload").path("payment").path("entity");
        String providerPaymentId = textOrNull(entity, "id");
        String providerOrderId = textOrNull(entity, "order_id");
        long amountMinor = entity.path("amount").asLong(-1);
        String method = textOrNull(entity, "method");

        if (providerOrderId == null || providerPaymentId == null) {
            log.warn("Capture event missing identifiers; ignoring");
            return;
        }

        Optional<PaymentEntity> maybePayment =
                payments.findByProviderAndProviderOrderId(PROVIDER, providerOrderId);
        if (maybePayment.isEmpty()) {
            log.warn("Capture for unknown gateway order {}", providerOrderId);
            return;
        }
        PaymentEntity payment = maybePayment.get();

        // Never trust the amount that comes back. A mismatch is a partial capture, a
        // currency confusion, or somebody replaying a cheap order's payment against
        // an expensive one — and all three are worth stopping.
        if (amountMinor != payment.getAmountMinor()) {
            log.error("Amount mismatch on gateway order {}: expected {}, received {}",
                    providerOrderId, payment.getAmountMinor(), amountMinor);
            payment.setFailureReason("amount_mismatch");
            payment.setStatus(PaymentStatus.FAILED);
            payments.save(payment);
            return;
        }

        payment.setProviderPaymentId(providerPaymentId);
        payment.setMethod(method);
        payment.setStatus(PaymentStatus.CAPTURED);
        payments.save(payment);

        orders.findById(payment.getOrderId())
                .ifPresent(order -> orderService.markPaid(order, providerPaymentId));
    }

    private void applyFailure(JsonNode root) {
        JsonNode entity = root.path("payload").path("payment").path("entity");
        String providerOrderId = textOrNull(entity, "order_id");
        String description = entity.path("error_description").asText("Payment failed");
        if (providerOrderId == null) {
            return;
        }
        payments.findByProviderAndProviderOrderId(PROVIDER, providerOrderId).ifPresent(payment -> {
            payment.setStatus(PaymentStatus.FAILED);
            payment.setFailureReason(description);
            payments.save(payment);
            // The order stays in AWAITING_PAYMENT on purpose. A failed attempt is not
            // an abandoned checkout — people retry with another method constantly, and
            // cancelling under them is the most irritating thing a storefront can do.
            // The stale-order sweep cleans up whatever never gets retried.
        });
    }

    private void applyRefund(JsonNode root) {
        JsonNode entity = root.path("payload").path("refund").path("entity");
        String providerPaymentId = textOrNull(entity, "payment_id");
        long amountMinor = entity.path("amount").asLong(0);
        if (providerPaymentId == null) {
            return;
        }
        payments.findByProviderAndProviderPaymentId(PROVIDER, providerPaymentId).ifPresent(payment -> {
            payment.setRefundedMinor(payment.getRefundedMinor() + amountMinor);
            if (payment.getRefundedMinor() >= payment.getAmountMinor()) {
                payment.setStatus(PaymentStatus.REFUNDED);
            }
            payments.save(payment);
            log.info("Recorded refund of {} against payment {}", amountMinor, providerPaymentId);
        });
    }

    /** {@code asText(null)} on a missing node returns the literal string "null". */
    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
