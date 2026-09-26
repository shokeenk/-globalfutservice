package com.globalfutservice.marketing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A promotional campaign, as composed in the admin panel. */
@Entity
@Table(name = "email_campaign")
public class CampaignEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false)
    private String heading;

    @Column(nullable = false)
    private String body;

    @Column(name = "promo_code")
    private String promoCode;

    @Column(name = "cta_text")
    private String ctaText;

    @Column(name = "cta_path")
    private String ctaPath;

    @Column(name = "banner_content_type")
    private String bannerContentType;

    /** Plain byte[] against BYTEA. See ManualPaymentProofEntity on why not the Lob annotation. */
    @Column(name = "banner_bytes")
    private byte[] bannerBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignAudience audience = CampaignAudience.ALL_OPTED_IN;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CampaignStatus status = CampaignStatus.DRAFT;

    @Column(name = "scheduled_at")
    private Instant scheduledAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    /** What the campaign is about; see {@link CampaignType}. Not who receives it. */
    @Enumerated(EnumType.STRING)
    @Column(name = "campaign_type", nullable = false)
    private CampaignType type = CampaignType.GENERAL;

    @Column(name = "offer_text")
    private String offerText;

    /** Last day the offer can be used, in the business's calendar. */
    @Column(name = "offer_valid_until")
    private LocalDate offerValidUntil;

    @Column(name = "show_promo_code", nullable = false)
    private boolean showPromoCode = true;

    @Column(name = "tracking_enabled", nullable = false)
    private boolean trackingEnabled = true;

    @Column(name = "hero_kicker")
    private String heroKicker;

    @Column(name = "hero_subline")
    private String heroSubline;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CampaignEntity() {
    }

    public CampaignEntity(String title, String subject, String heading, String body,
                          CampaignAudience audience, Long createdBy) {
        this.publicId = "camp_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        this.title = title;
        this.subject = subject;
        this.heading = heading;
        this.body = body;
        this.audience = audience;
        this.createdBy = createdBy;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public boolean hasBanner() {
        return bannerBytes != null && bannerBytes.length > 0;
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public String getTitle() { return title; }
    public void setTitle(String v) { this.title = v; }
    public String getSubject() { return subject; }
    public void setSubject(String v) { this.subject = v; }
    public String getHeading() { return heading; }
    public void setHeading(String v) { this.heading = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public String getPromoCode() { return promoCode; }
    public void setPromoCode(String v) { this.promoCode = v; }
    public String getCtaText() { return ctaText; }
    public void setCtaText(String v) { this.ctaText = v; }
    public String getCtaPath() { return ctaPath; }
    public void setCtaPath(String v) { this.ctaPath = v; }
    public String getBannerContentType() { return bannerContentType; }
    public byte[] getBannerBytes() { return bannerBytes; }

    public void setBanner(String contentType, byte[] bytes) {
        this.bannerContentType = contentType;
        this.bannerBytes = bytes;
    }

    public CampaignAudience getAudience() { return audience; }
    public void setAudience(CampaignAudience v) { this.audience = v; }
    public CampaignStatus getStatus() { return status; }
    public void setStatus(CampaignStatus v) { this.status = v; }
    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant v) { this.scheduledAt = v; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant v) { this.startedAt = v; }
    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant v) { this.completedAt = v; }
    public CampaignType getType() { return type; }
    public void setType(CampaignType v) { this.type = v; }
    public String getOfferText() { return offerText; }
    public void setOfferText(String v) { this.offerText = v; }
    public LocalDate getOfferValidUntil() { return offerValidUntil; }
    public void setOfferValidUntil(LocalDate v) { this.offerValidUntil = v; }
    public boolean isShowPromoCode() { return showPromoCode; }
    public void setShowPromoCode(boolean v) { this.showPromoCode = v; }
    public boolean isTrackingEnabled() { return trackingEnabled; }
    public void setTrackingEnabled(boolean v) { this.trackingEnabled = v; }
    public String getHeroKicker() { return heroKicker; }
    public void setHeroKicker(String v) { this.heroKicker = v; }
    public String getHeroSubline() { return heroSubline; }
    public void setHeroSubline(String v) { this.heroSubline = v; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
