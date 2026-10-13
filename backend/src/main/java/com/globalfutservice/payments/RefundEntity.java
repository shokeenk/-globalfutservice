package com.globalfutservice.payments;

import com.globalfutservice.domain.money.Currency;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/**
 * Money sent back to a customer by hand, and how. At most one of each kind per order: the
 * full refund, and a Champions settlement paid back for wins not achieved (V47).
 */
@Entity
@Table(name = "refund")
public class RefundEntity {

    public enum Kind {
        /** The order's total, less anything already settled; the order is Refunded. */
        FULL,
        /** A Champions settlement. The order is not Refunded by it. */
        SETTLEMENT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private RefundMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Kind kind = Kind.FULL;

    @Column(nullable = false, updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RefundEntity() {
    }

    public RefundEntity(Long orderId, long amountMinor, Currency currency, RefundMethod method,
                        String reference, String reason, Long createdBy, Kind kind) {
        this(orderId, amountMinor, currency, method, reference, reason, createdBy);
        this.kind = kind;
    }

    public RefundEntity(Long orderId, long amountMinor, Currency currency, RefundMethod method,
                        String reference, String reason, Long createdBy) {
        this.orderId = orderId;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.method = method;
        this.reference = reference;
        this.reason = reason;
        this.createdBy = createdBy;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Currency getCurrency() {
        return currency;
    }

    public RefundMethod getMethod() {
        return method;
    }

    public Kind getKind() {
        return kind;
    }

    public String getReference() {
        return reference;
    }

    public String getReason() {
        return reason;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
