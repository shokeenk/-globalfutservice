package com.globalfutservice.marketing;

import java.time.Instant;

import com.globalfutservice.identity.ConsentSource;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One change of a customer's marketing choice: which way, how, and when. See V58. */
@Entity
@Table(name = "marketing_consent_event")
public class MarketingConsentEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Column(name = "opted_in", nullable = false, updatable = false)
    private boolean optedIn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private ConsentSource source;

    @Column(name = "campaign_id", updatable = false)
    private Long campaignId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected MarketingConsentEventEntity() {
    }

    public MarketingConsentEventEntity(Long accountId, boolean optedIn, ConsentSource source, Long campaignId,
                                       Instant createdAt) {
        this.accountId = accountId;
        this.optedIn = optedIn;
        this.source = source;
        this.campaignId = campaignId;
        this.createdAt = createdAt;
    }

    public Long getAccountId() {
        return accountId;
    }

    public boolean isOptedIn() {
        return optedIn;
    }

    public ConsentSource getSource() {
        return source;
    }

    public Long getCampaignId() {
        return campaignId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
