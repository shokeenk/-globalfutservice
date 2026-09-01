package com.globalfutservice.payments;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * One received webhook delivery, kept for idempotency and for forensics.
 *
 * <p>Gateways retry. Without the unique constraint on {@code (provider, providerEventId)},
 * a retried {@code payment.captured} would run the whole post-payment path twice — a
 * second confirmation email, a second WhatsApp alert, and in a worse design a second
 * points grant. Recording the delivery first and short-circuiting on conflict makes the
 * handler idempotent at the database level rather than by convention.
 */
@Entity
@Table(name = "webhook_event")
public class WebhookEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String provider;

    @Column(name = "provider_event_id", nullable = false)
    private String providerEventId;

    @Column(name = "event_type")
    private String eventType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String payload;

    @Column(name = "received_at", nullable = false, updatable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @Column(name = "process_error")
    private String processError;

    protected WebhookEventEntity() {
    }

    public WebhookEventEntity(String provider, String providerEventId, String eventType, String payload) {
        this.provider = provider;
        this.providerEventId = providerEventId;
        this.eventType = eventType;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public String getEventType() {
        return eventType;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getProcessedAt() {
        return processedAt;
    }

    public void markProcessed(Instant at) {
        this.processedAt = at;
        this.processError = null;
    }

    public void markFailed(String error) {
        this.processError = error;
    }
}
