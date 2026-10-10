package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One Payop payment method and what it costs: a fixed part in EUR plus a percentage. */
@Entity
@Table(name = "payop_fee_method")
public class PayopFeeMethodEntity {

    @Id
    @Column(name = "method_id")
    private Long methodId;

    @Column(nullable = false)
    private String name;

    @Column(name = "method_type", nullable = false)
    private String methodType;

    private String region;

    @Column(name = "fixed_eur", nullable = false)
    private BigDecimal fixedEur;

    @Column(nullable = false)
    private BigDecimal percent;

    /** ISO codes, comma-separated, or "*" for every country. */
    @Column(nullable = false)
    private String countries;

    @Column(nullable = false)
    private String currencies;

    @Column(nullable = false)
    private boolean active = true;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Where the row came from: SHEET, the pricing sheet's import, or MANUAL, an admin's own
     * entry. Importing the sheet again leaves a MANUAL row alone unless the sheet lists it.
     */
    @Column(nullable = false)
    private String source = SHEET;

    public static final String SHEET = "SHEET";
    public static final String MANUAL = "MANUAL";

    protected PayopFeeMethodEntity() {
        // JPA
    }

    public PayopFeeMethodEntity(long methodId, String name, String methodType, String region, BigDecimal fixedEur,
                                BigDecimal percent, List<String> countries, List<String> currencies,
                                Long updatedBy, Instant updatedAt) {
        this.methodId = methodId;
        this.name = name;
        this.methodType = methodType;
        this.region = region;
        this.fixedEur = fixedEur;
        this.percent = percent;
        this.countries = String.join(",", countries);
        this.currencies = String.join(",", currencies);
        this.updatedBy = updatedBy;
        this.updatedAt = updatedAt;
    }

    /** A new price or availability: the version moves on, so old invoices keep naming the old one. */
    /** A method an admin priced by hand, outside the sheet. */
    public static PayopFeeMethodEntity manual(long methodId, String name, String methodType, String region,
                                              BigDecimal fixedEur, BigDecimal percent, List<String> countries,
                                              List<String> currencies, boolean active, Long by, Instant at) {
        PayopFeeMethodEntity row = new PayopFeeMethodEntity(methodId, name, methodType, region, fixedEur, percent,
                countries, currencies, by, at);
        row.active = active;
        row.source = MANUAL;
        return row;
    }

    /** The sheet lists this method now: imports manage it from here on. */
    public void takenBySheet() {
        this.source = SHEET;
    }

    public boolean isManual() {
        return MANUAL.equals(source);
    }

    public String getSource() {
        return source;
    }

    public void change(String name, String methodType, String region, BigDecimal fixedEur, BigDecimal percent,
                       List<String> countries, List<String> currencies, boolean active, Long by, Instant at) {
        this.name = name;
        this.methodType = methodType;
        this.region = region;
        this.fixedEur = fixedEur;
        this.percent = percent;
        this.countries = String.join(",", countries);
        this.currencies = String.join(",", currencies);
        this.active = active;
        this.version++;
        this.updatedBy = by;
        this.updatedAt = at;
    }

    public List<String> countryList() {
        return split(countries);
    }

    public List<String> currencyList() {
        return split(currencies);
    }

    private static List<String> split(String s) {
        return s == null || s.isBlank() ? List.of() : Arrays.stream(s.split(",")).map(String::trim).toList();
    }

    public Long getMethodId() {
        return methodId;
    }

    public String getName() {
        return name;
    }

    public String getMethodType() {
        return methodType;
    }

    public String getRegion() {
        return region;
    }

    public BigDecimal getFixedEur() {
        return fixedEur;
    }

    public BigDecimal getPercent() {
        return percent;
    }

    public boolean isActive() {
        return active;
    }

    public int getVersion() {
        return version;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
