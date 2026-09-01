package com.globalfutservice.payments;

import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.payments.PaymentStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "payment")
public class PaymentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(nullable = false)
    private String provider = "RAZORPAY";

    @Column(name = "provider_order_id", nullable = false)
    private String providerOrderId;

    @Column(name = "provider_payment_id")
    private String providerPaymentId;

    /** upi, card, netbanking — useful for the "not available in my area" conversation. */
    private String method;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Enumerated(EnumType.STRING)
    // The column is CHAR(3), not VARCHAR — a currency code is exactly three characters
    // and V1__baseline.sql says so. Hibernate assumes varchar(255) for a string enum, so
    // without this the schema validator rejects the real column and startup fails.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status = PaymentStatus.CREATED;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "refunded_minor", nullable = false)
    private long refundedMinor;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected PaymentEntity() {
    }

    public PaymentEntity(Long orderId, String providerOrderId, long amountMinor, Currency currency) {
        this.orderId = orderId;
        this.providerOrderId = providerOrderId;
        this.amountMinor = amountMinor;
        this.currency = currency;
    }

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public String getProviderOrderId() {
        return providerOrderId;
    }

    public String getProviderPaymentId() {
        return providerPaymentId;
    }

    public void setProviderPaymentId(String providerPaymentId) {
        this.providerPaymentId = providerPaymentId;
    }

    public String getMethod() {
        return method;
    }

    public void setMethod(String method) {
        this.method = method;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public Currency getCurrency() {
        return currency;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public long getRefundedMinor() {
        return refundedMinor;
    }

    public void setRefundedMinor(long refundedMinor) {
        this.refundedMinor = refundedMinor;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
