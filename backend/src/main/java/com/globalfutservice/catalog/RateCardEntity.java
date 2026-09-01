package com.globalfutservice.catalog;

import com.globalfutservice.domain.catalog.Platform;
import com.globalfutservice.domain.catalog.PriceUnit;
import com.globalfutservice.domain.catalog.RateCard;
import com.globalfutservice.domain.catalog.Sku;
import com.globalfutservice.domain.money.Currency;
import com.globalfutservice.domain.money.Money;
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

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Persistence view of a rate-card row.
 *
 * <p>Temporal by design: {@code validTo} of null means "this is the live price". Editing
 * a price closes the current row and inserts a new one, so nothing is ever destroyed and
 * every historical order can still be explained.
 */
@Entity
@Table(name = "rate_card")
public class RateCardEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String season;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sku sku;

    @Enumerated(EnumType.STRING)
    private Platform platform;

    private String variant;

    @Enumerated(EnumType.STRING)
    // The column is CHAR(3), not VARCHAR — a currency code is exactly three characters
    // and V1__baseline.sql says so. Hibernate assumes varchar(255) for a string enum, so
    // without this the schema validator rejects the real column and startup fails.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private Currency currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_unit", nullable = false)
    private PriceUnit priceUnit;

    @Column(name = "unit_price_minor", nullable = false)
    private long unitPriceMinor;

    @Column(name = "min_quantity")
    private BigDecimal minQuantity;

    @Column(name = "max_quantity")
    private BigDecimal maxQuantity;

    @Column(name = "step_quantity")
    private BigDecimal stepQuantity;

    private String label;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom = Instant.now();

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected RateCardEntity() {
    }

    public RateCardEntity(String season, Sku sku, Platform platform, String variant,
                          Currency currency, PriceUnit priceUnit, long unitPriceMinor,
                          BigDecimal minQuantity, BigDecimal maxQuantity, BigDecimal stepQuantity,
                          String label, int sortOrder, Long createdBy) {
        this.season = season;
        this.sku = sku;
        this.platform = platform;
        this.variant = variant;
        this.currency = currency;
        this.priceUnit = priceUnit;
        this.unitPriceMinor = unitPriceMinor;
        this.minQuantity = minQuantity;
        this.maxQuantity = maxQuantity;
        this.stepQuantity = stepQuantity;
        this.label = label;
        this.sortOrder = sortOrder;
        this.createdBy = createdBy;
    }

    /** Projects to the framework-free value object the pricing engine consumes. */
    public RateCard toDomain() {
        return new RateCard(
                season, sku, platform, variant, label,
                Money.ofMinor(unitPriceMinor, currency),
                minQuantity, maxQuantity, stepQuantity);
    }

    public boolean isLive() {
        return validTo == null;
    }

    public void close(Instant at) {
        this.validTo = at;
    }

    public Long getId() {
        return id;
    }

    public String getSeason() {
        return season;
    }

    public Sku getSku() {
        return sku;
    }

    public Platform getPlatform() {
        return platform;
    }

    public String getVariant() {
        return variant;
    }

    public Currency getCurrency() {
        return currency;
    }

    public PriceUnit getPriceUnit() {
        return priceUnit;
    }

    public long getUnitPriceMinor() {
        return unitPriceMinor;
    }

    public BigDecimal getMinQuantity() {
        return minQuantity;
    }

    public BigDecimal getMaxQuantity() {
        return maxQuantity;
    }

    public BigDecimal getStepQuantity() {
        return stepQuantity;
    }

    public String getLabel() {
        return label;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public Instant getValidFrom() {
        return validFrom;
    }

    public Instant getValidTo() {
        return validTo;
    }
}
