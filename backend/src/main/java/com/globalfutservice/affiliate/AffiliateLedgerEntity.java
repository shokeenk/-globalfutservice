package com.globalfutservice.affiliate;

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

/** Commission accrued on one order. Unique per order, whatever a retry does. */
@Entity
@Table(name = "affiliate_ledger")
public class AffiliateLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "affiliate_id", nullable = false)
    private Long affiliateId;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "gross_minor", nullable = false)
    private long grossMinor;

    @Column(name = "commission_minor", nullable = false)
    private long commissionMinor;

    @Enumerated(EnumType.STRING)
    // The column is CHAR(3), not VARCHAR — a currency code is exactly three characters
    // and V1__baseline.sql says so. Hibernate assumes varchar(255) for a string enum, so
    // without this the schema validator rejects the real column and startup fails.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(name = "paid_at")
    private Instant paidAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected AffiliateLedgerEntity() {
    }

    public AffiliateLedgerEntity(Long affiliateId, Long orderId, long grossMinor,
                                 long commissionMinor, Currency currency, String status) {
        this.affiliateId = affiliateId;
        this.orderId = orderId;
        this.grossMinor = grossMinor;
        this.commissionMinor = commissionMinor;
        this.currency = currency;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public Long getAffiliateId() {
        return affiliateId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public long getGrossMinor() {
        return grossMinor;
    }

    public long getCommissionMinor() {
        return commissionMinor;
    }

    public Currency getCurrency() {
        return currency;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getPaidAt() {
        return paidAt;
    }

    public void setPaidAt(Instant paidAt) {
        this.paidAt = paidAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
