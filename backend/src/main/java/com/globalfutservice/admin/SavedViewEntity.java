package com.globalfutservice.admin;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

/** A named set of filters one staff member saved on a console page. */
@Entity
@Table(name = "admin_saved_view")
public class SavedViewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(nullable = false, updatable = false)
    private String page;

    @Column(nullable = false)
    private String name;

    /** A flat JSON object of filter keys to string values, checked before it is stored. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String filters;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected SavedViewEntity() {
    }

    public SavedViewEntity(Long accountId, String page, String name, String filters) {
        this.accountId = accountId;
        this.page = page;
        this.name = name;
        this.filters = filters;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public String getPage() {
        return page;
    }

    public String getName() {
        return name;
    }

    public String getFilters() {
        return filters;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
