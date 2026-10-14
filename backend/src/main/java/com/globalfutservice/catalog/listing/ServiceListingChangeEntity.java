package com.globalfutservice.catalog.listing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/** Who changed a listing, when, and what they did (V49). Append-only. */
@Entity
@Table(name = "service_listing_change")
public class ServiceListingChangeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "listing_id", nullable = false)
    private Long listingId;

    @Column(nullable = false)
    private String action;

    private String detail;

    @Column(name = "admin_id")
    private Long adminId;

    @Column(nullable = false)
    private Instant at;

    protected ServiceListingChangeEntity() {
    }

    public ServiceListingChangeEntity(Long listingId, String action, String detail, Long adminId, Instant at) {
        this.listingId = listingId;
        this.action = action;
        this.detail = detail;
        this.adminId = adminId;
        this.at = at;
    }

    public Long getListingId() {
        return listingId;
    }

    public String getAction() {
        return action;
    }

    public String getDetail() {
        return detail;
    }

    public Long getAdminId() {
        return adminId;
    }

    public Instant getAt() {
        return at;
    }
}
