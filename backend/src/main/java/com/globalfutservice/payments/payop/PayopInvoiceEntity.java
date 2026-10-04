package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

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

/**
 * One attempt to pay an order through Payop, with the fee exactly as the customer was shown it.
 *
 * <p>Nothing about the price changes after creation: a later fee or rate change never
 * touches an attempt already made. Only the status, Payop's IDs and the review reason move.
 */
@Entity
@Table(name = "payop_invoice")
public class PayopInvoiceEntity {

    /** Where an attempt stands. CREATING and OPEN are the "active" ones: at most one per order. */
    public enum Status { CREATING, OPEN, PAID, FAILED, EXPIRED, REVIEW, DUPLICATE }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "attempt_id", nullable = false, unique = true)
    private UUID attemptId;

    @Column(name = "invoice_id")
    private String invoiceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.CREATING;

    @Column(name = "method_id", nullable = false)
    private Long methodId;

    @Column(name = "method_name", nullable = false)
    private String methodName;

    @Column(name = "method_version", nullable = false)
    private int methodVersion;

    @Column(name = "fixed_eur", nullable = false)
    private BigDecimal fixedEur;

    @Column(nullable = false)
    private BigDecimal percent;

    @Column(name = "fx_rate", nullable = false)
    private BigDecimal fxRate;

    @Column(name = "fx_source", nullable = false)
    private String fxSource;

    @Column(name = "fx_date", nullable = false)
    private LocalDate fxDate;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(name = "net_minor", nullable = false)
    private long netMinor;

    @Column(name = "fee_minor", nullable = false)
    private long feeMinor;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @Column(name = "amount_sent", nullable = false)
    private String amountSent;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    private String country;

    private String txid;

    @Column(name = "review_reason")
    private String reviewReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PayopInvoiceEntity() {
        // JPA
    }

    public PayopInvoiceEntity(long orderId, UUID attemptId, PayopFeeMethodEntity method, FxRateService.RateUsed rate,
                              Currency currency, PayopFeeCalculator.FeeQuote fee, String amountSent, String country,
                              Instant createdAt, Instant expiresAt) {
        this.orderId = orderId;
        this.attemptId = attemptId;
        this.methodId = method.getMethodId();
        this.methodName = method.getName();
        this.methodVersion = method.getVersion();
        this.fixedEur = method.getFixedEur();
        this.percent = method.getPercent();
        this.fxRate = rate.rate();
        this.fxSource = rate.source();
        this.fxDate = rate.date();
        this.currency = currency;
        this.netMinor = fee.netMinor();
        this.feeMinor = fee.feeMinor();
        this.totalMinor = fee.totalMinor();
        this.amountSent = amountSent;
        this.country = country;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.updatedAt = createdAt;
    }

    public void opened(String payopInvoiceId, Instant at) {
        this.invoiceId = payopInvoiceId;
        this.status = Status.OPEN;
        this.updatedAt = at;
    }

    public void paid(String txid, Instant at) {
        this.txid = txid;
        this.status = Status.PAID;
        this.paidAt = at;
        this.updatedAt = at;
    }

    public void failed(String txid, String reason, Instant at) {
        this.txid = txid;
        this.status = Status.FAILED;
        this.reviewReason = reason;
        this.updatedAt = at;
    }

    /** No longer offered: its 24 hours are up, or the customer chose another method. */
    public void expired(String reason, Instant at) {
        this.status = Status.EXPIRED;
        this.reviewReason = reason;
        this.updatedAt = at;
    }

    /** Needs a person: the money may have moved, but something about it did not match. */
    public void review(String txid, String reason, Instant at) {
        this.txid = txid == null ? this.txid : txid;
        this.status = Status.REVIEW;
        this.reviewReason = reason;
        this.updatedAt = at;
    }

    /** Paid, but the order was already paid: the money has to go back. */
    public void duplicate(String txid, String reason, Instant at) {
        this.txid = txid;
        this.status = Status.DUPLICATE;
        this.reviewReason = reason;
        this.updatedAt = at;
    }

    public boolean isActive() {
        return status == Status.CREATING || status == Status.OPEN;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public UUID getAttemptId() {
        return attemptId;
    }

    public String getInvoiceId() {
        return invoiceId;
    }

    public Status getStatus() {
        return status;
    }

    public Long getMethodId() {
        return methodId;
    }

    public String getMethodName() {
        return methodName;
    }

    public int getMethodVersion() {
        return methodVersion;
    }

    public BigDecimal getFixedEur() {
        return fixedEur;
    }

    public BigDecimal getPercent() {
        return percent;
    }

    public BigDecimal getFxRate() {
        return fxRate;
    }

    public String getFxSource() {
        return fxSource;
    }

    public LocalDate getFxDate() {
        return fxDate;
    }

    public Currency getCurrency() {
        return currency;
    }

    public long getNetMinor() {
        return netMinor;
    }

    public long getFeeMinor() {
        return feeMinor;
    }

    public long getTotalMinor() {
        return totalMinor;
    }

    public String getAmountSent() {
        return amountSent;
    }

    public String getCountry() {
        return country;
    }

    public String getTxid() {
        return txid;
    }

    public String getReviewReason() {
        return reviewReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
