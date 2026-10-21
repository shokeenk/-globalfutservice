package com.globalfutservice.popups;

import java.time.Instant;

import com.globalfutservice.popups.PopupTypes.Frequency;
import com.globalfutservice.popups.PopupTypes.Status;
import com.globalfutservice.popups.PopupTypes.Trigger;
import com.globalfutservice.popups.PopupTypes.Type;
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

/**
 * One popup campaign. See V59. The JSON columns are held as text and read and written
 * through {@link PopupService}, which is the only place that checks what goes into them.
 */
@Entity
@Table(name = "popup_campaign")
public class PopupCampaignEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private String publicId;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(name = "desktop_image_id")
    private Long desktopImageId;

    @Column(name = "mobile_image_id")
    private Long mobileImageId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String copy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private String buttons = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_pages", nullable = false, columnDefinition = "jsonb")
    private String targetPages = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "target_paths", nullable = false, columnDefinition = "jsonb")
    private String targetPaths = "[]";

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false)
    private Trigger triggerType = Trigger.PAGE_LOAD;

    @Column(name = "trigger_value")
    private Integer triggerValue;

    @Column(name = "trigger_event")
    private String triggerEvent;

    @Column(name = "starts_at")
    private Instant startsAt;

    @Column(name = "ends_at")
    private Instant endsAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Frequency frequency = Frequency.SESSION;

    @Column(name = "frequency_days")
    private Integer frequencyDays;

    @Column(nullable = false)
    private int priority;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private int version = 1;

    @Column(name = "created_by", updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected PopupCampaignEntity() {
    }

    public PopupCampaignEntity(String publicId, String name, Type type, String copy, Long adminId, Instant now) {
        this.publicId = publicId;
        this.name = name;
        this.type = type;
        this.copy = copy;
        this.createdBy = adminId;
        this.createdAt = now;
        this.updatedBy = adminId;
        this.updatedAt = now;
    }

    /** Every change: who, when, and a new version so browsers that saw the old one see it again. */
    public void touched(Long adminId, Instant now) {
        this.updatedBy = adminId;
        this.updatedAt = now;
        this.version++;
    }

    public boolean isLive(Instant now) {
        return deletedAt == null && enabled && status == Status.PUBLISHED
                && (startsAt == null || !startsAt.isAfter(now))
                && (endsAt == null || endsAt.isAfter(now));
    }

    public Long getId() { return id; }
    public String getPublicId() { return publicId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public Long getDesktopImageId() { return desktopImageId; }
    public void setDesktopImageId(Long id) { this.desktopImageId = id; }
    public Long getMobileImageId() { return mobileImageId; }
    public void setMobileImageId(Long id) { this.mobileImageId = id; }
    public String getCopy() { return copy; }
    public void setCopy(String copy) { this.copy = copy; }
    public String getButtons() { return buttons; }
    public void setButtons(String buttons) { this.buttons = buttons; }
    public String getTargetPages() { return targetPages; }
    public void setTargetPages(String targetPages) { this.targetPages = targetPages; }
    public String getTargetPaths() { return targetPaths; }
    public void setTargetPaths(String targetPaths) { this.targetPaths = targetPaths; }
    public Trigger getTriggerType() { return triggerType; }
    public void setTriggerType(Trigger triggerType) { this.triggerType = triggerType; }
    public Integer getTriggerValue() { return triggerValue; }
    public void setTriggerValue(Integer triggerValue) { this.triggerValue = triggerValue; }
    public String getTriggerEvent() { return triggerEvent; }
    public void setTriggerEvent(String triggerEvent) { this.triggerEvent = triggerEvent; }
    public Instant getStartsAt() { return startsAt; }
    public void setStartsAt(Instant startsAt) { this.startsAt = startsAt; }
    public Instant getEndsAt() { return endsAt; }
    public void setEndsAt(Instant endsAt) { this.endsAt = endsAt; }
    public Frequency getFrequency() { return frequency; }
    public void setFrequency(Frequency frequency) { this.frequency = frequency; }
    public Integer getFrequencyDays() { return frequencyDays; }
    public void setFrequencyDays(Integer frequencyDays) { this.frequencyDays = frequencyDays; }
    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
    public Instant getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Instant deletedAt) { this.deletedAt = deletedAt; }
}
