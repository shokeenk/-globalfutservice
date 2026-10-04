package com.globalfutservice.payments.payop;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** How many units of {@code quote} one EUR buys, from the ECB or entered by an admin. */
@Entity
@Table(name = "fx_rate")
public class FxRateEntity {

    public static final String ECB = "ECB";
    public static final String ADMIN = "ADMIN";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String base = "EUR";

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, length = 3)
    private String quote;

    @Column(nullable = false)
    private BigDecimal rate;

    @Column(nullable = false)
    private String source;

    @Column(name = "rate_date", nullable = false)
    private LocalDate rateDate;

    @Column(name = "fetched_at", nullable = false)
    private Instant fetchedAt;

    @Column(name = "entered_by")
    private Long enteredBy;

    protected FxRateEntity() {
        // JPA
    }

    public FxRateEntity(String quote, BigDecimal rate, String source, LocalDate rateDate, Instant fetchedAt,
                        Long enteredBy) {
        this.quote = quote;
        this.rate = rate;
        this.source = source;
        this.rateDate = rateDate;
        this.fetchedAt = fetchedAt;
        this.enteredBy = enteredBy;
    }

    public Long getId() {
        return id;
    }

    public String getQuote() {
        return quote;
    }

    public BigDecimal getRate() {
        return rate;
    }

    public String getSource() {
        return source;
    }

    public LocalDate getRateDate() {
        return rateDate;
    }

    public Instant getFetchedAt() {
        return fetchedAt;
    }

    public Long getEnteredBy() {
        return enteredBy;
    }
}
