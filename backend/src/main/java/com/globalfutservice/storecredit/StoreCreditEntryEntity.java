package com.globalfutservice.storecredit;

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
 * One movement of GFS Store Credit, in one currency. Append-only: a balance is the sum of
 * a customer's rows in that currency (V48).
 */
@Entity
@Table(name = "store_credit_ledger")
public class StoreCreditEntryEntity {

    public enum Type {
        /** A settlement paid as store credit. Positive. */
        ISSUED,
        /** Used on an order at checkout. Negative. */
        SPENT,
        /** Given back from an order that was abandoned or refunded. Positive. */
        RETURNED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3, updatable = false)
    private Currency currency;

    @Column(name = "amount_minor", nullable = false, updatable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, updatable = false)
    private Type entryType;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(nullable = false, updatable = false)
    private String description;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected StoreCreditEntryEntity() {
    }

    /** {@code magnitude} is never negative: the type carries the sign. */
    public StoreCreditEntryEntity(Long accountId, Currency currency, Type type, long magnitude, Long orderId,
                                  String description, Long createdBy, Instant at) {
        if (magnitude <= 0) {
            throw new IllegalArgumentException("A store credit entry moves a positive amount");
        }
        this.accountId = accountId;
        this.currency = currency;
        this.entryType = type;
        this.amountMinor = type == Type.SPENT ? -magnitude : magnitude;
        this.orderId = orderId;
        this.description = description;
        this.createdBy = createdBy;
        this.createdAt = at;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Currency getCurrency() {
        return currency;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Type getEntryType() {
        return entryType;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getDescription() {
        return description;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
