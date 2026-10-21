package com.globalfutservice.identity;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The single row of sign-up settings. See V58. */
@Entity
@Table(name = "signup_settings")
public class SignupSettingsEntity {

    static final short ONLY = 1;

    @Id
    private Short id = ONLY;

    @Column(name = "prefill_marketing_consent", nullable = false)
    private boolean prefillMarketingConsent;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected SignupSettingsEntity() {
    }

    public boolean isPrefillMarketingConsent() {
        return prefillMarketingConsent;
    }

    public void update(boolean prefillMarketingConsent, Long by, Instant at) {
        this.prefillMarketingConsent = prefillMarketingConsent;
        this.updatedBy = by;
        this.updatedAt = at;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
