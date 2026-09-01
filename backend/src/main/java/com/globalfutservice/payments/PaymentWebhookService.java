package com.globalfutservice.payments;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Orchestrates a verified gateway webhook: record it, apply it, mark the outcome.
 *
 * <p>This is the only path in the application that can mark an order paid. The
 * browser redirect Razorpay Checkout produces decides what the customer sees next
 * and nothing else — it can be closed, replayed, or hand-crafted, and a business
 * that trusts it will eventually deliver against a payment that never happened.
 *
 * <p>Deliberately <b>not</b> transactional itself. Each of the three steps runs in
 * its own transaction on its own bean, so a failure while applying an event still
 * leaves a durable audit row describing that failure — which is exactly the record
 * you want when a customer says they paid and the order says otherwise.
 */
@Service
public class PaymentWebhookService {

    private static final Logger log = LoggerFactory.getLogger(PaymentWebhookService.class);
    private static final String PROVIDER = "RAZORPAY";

    private final WebhookLedger ledger;
    private final PaymentApplier applier;
    private final ObjectMapper mapper;

    public PaymentWebhookService(WebhookLedger ledger, PaymentApplier applier, ObjectMapper mapper) {
        this.ledger = ledger;
        this.applier = applier;
        this.mapper = mapper;
    }

    /**
     * @param eventId the gateway's own delivery id, used as the idempotency key
     * @return true if this delivery was new and has been applied
     */
    public boolean handle(String eventId, String rawBody) {
        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            log.warn("Webhook {} had an unreadable body", eventId);
            return false;
        }

        String eventType = root.path("event").asText("unknown");

        Optional<Long> recordId = ledger.record(PROVIDER, eventId, eventType, rawBody);
        if (recordId.isEmpty()) {
            // Seen before. Gateways retry aggressively; this is the normal case.
            return false;
        }

        try {
            applier.apply(eventType, root);
            ledger.markProcessed(recordId.get());
        } catch (Exception e) {
            // Recorded, not re-thrown. Re-throwing would make the gateway retry
            // forever against an application bug rather than a transient fault; the
            // stored delivery can be replayed deliberately once the bug is fixed.
            log.error("Webhook {} ({}) failed to apply", eventId, eventType, e);
            ledger.markFailed(recordId.get(), e.getClass().getSimpleName());
        }
        return true;
    }
}
