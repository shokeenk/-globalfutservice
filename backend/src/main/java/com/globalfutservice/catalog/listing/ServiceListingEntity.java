package com.globalfutservice.catalog.listing;

import com.globalfutservice.domain.catalog.Sku;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * An Objectives or Tournaments listing as the GFS team wrote it (V49): what the card and
 * the checkout show. Its prices are rate card rows under {@link #variant()}.
 */
@Entity
@Table(name = "service_listing")
public class ServiceListingEntity {

    /** The rate card variant of listing {@code id}: "LISTING_12". */
    public static final String VARIANT_PREFIX = "LISTING_";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Sku sku;

    @Column(nullable = false)
    private String title;

    private String subtitle;
    private String overview;
    private String requirements;

    @Column(name = "required_games")
    private Integer requiredGames;

    @Column(name = "estimated_time")
    private String estimatedTime;

    @Column(name = "scheduling_note")
    private String schedulingNote;

    private String reward;

    @Column(name = "customer_instructions")
    private String customerInstructions;

    @Enumerated(EnumType.STRING)
    @Column(name = "pricing_type", nullable = false)
    private PricingType pricingType = PricingType.FIXED;

    @Column(name = "max_hours", nullable = false)
    private int maxHours = ServiceListingService.DEFAULT_MAX_HOURS;

    @Column(name = "show_image", nullable = false)
    private boolean showImage = true;

    @Column(name = "image_id")
    private Long imageId;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "display_order", nullable = false)
    private int displayOrder;

    @Column(name = "seed_key")
    private String seedKey;

    @Column(name = "created_by")
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_by")
    private Long deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    public enum PricingType { FIXED, PER_HOUR }

    protected ServiceListingEntity() {
    }

    public ServiceListingEntity(Sku sku, String title, Long createdBy, Instant at) {
        this.sku = sku;
        this.title = title;
        this.createdBy = createdBy;
        this.createdAt = at;
        this.updatedBy = createdBy;
        this.updatedAt = at;
    }

    public static String variantOf(long id) {
        return VARIANT_PREFIX + id;
    }

    /** The listing id a rate card variant names, or null when it names none. */
    public static Long idOf(String variant) {
        if (variant == null || !variant.startsWith(VARIANT_PREFIX)) {
            return null;
        }
        try {
            return Long.parseLong(variant.substring(VARIANT_PREFIX.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public String variant() {
        return variantOf(id);
    }

    public boolean onSale() {
        return active && deletedAt == null;
    }

    public boolean deleted() {
        return deletedAt != null;
    }

    void touched(Long by, Instant at) {
        this.updatedBy = by;
        this.updatedAt = at;
    }

    void delete(Long by, Instant at) {
        this.active = false;
        this.deletedBy = by;
        this.deletedAt = at;
        touched(by, at);
    }

    public Long getId() {
        return id;
    }

    public Sku getSku() {
        return sku;
    }

    void setSku(Sku sku) {
        this.sku = sku;
    }

    public String getTitle() {
        return title;
    }

    void setTitle(String title) {
        this.title = title;
    }

    public String getSubtitle() {
        return subtitle;
    }

    void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public String getOverview() {
        return overview;
    }

    void setOverview(String overview) {
        this.overview = overview;
    }

    public String getRequirements() {
        return requirements;
    }

    void setRequirements(String requirements) {
        this.requirements = requirements;
    }

    public Integer getRequiredGames() {
        return requiredGames;
    }

    void setRequiredGames(Integer requiredGames) {
        this.requiredGames = requiredGames;
    }

    public String getEstimatedTime() {
        return estimatedTime;
    }

    void setEstimatedTime(String estimatedTime) {
        this.estimatedTime = estimatedTime;
    }

    public String getSchedulingNote() {
        return schedulingNote;
    }

    void setSchedulingNote(String schedulingNote) {
        this.schedulingNote = schedulingNote;
    }

    public String getReward() {
        return reward;
    }

    void setReward(String reward) {
        this.reward = reward;
    }

    public String getCustomerInstructions() {
        return customerInstructions;
    }

    void setCustomerInstructions(String customerInstructions) {
        this.customerInstructions = customerInstructions;
    }

    public PricingType getPricingType() {
        return pricingType;
    }

    void setPricingType(PricingType pricingType) {
        this.pricingType = pricingType;
    }

    public int getMaxHours() {
        return maxHours;
    }

    void setMaxHours(int maxHours) {
        this.maxHours = maxHours;
    }

    public boolean isShowImage() {
        return showImage;
    }

    void setShowImage(boolean showImage) {
        this.showImage = showImage;
    }

    public Long getImageId() {
        return imageId;
    }

    void setImageId(Long imageId) {
        this.imageId = imageId;
    }

    public boolean isActive() {
        return active;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }

    public String getSeedKey() {
        return seedKey;
    }

    void setSeedKey(String seedKey) {
        this.seedKey = seedKey;
    }

    public Long getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Long getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getDeletedBy() {
        return deletedBy;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }
}
