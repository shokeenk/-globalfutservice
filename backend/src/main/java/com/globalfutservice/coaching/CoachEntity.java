package com.globalfutservice.coaching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.time.ZoneId;

/**
 * Someone who teaches.
 *
 * <p>Linked to an {@code account} where one exists, so a coach signs in through the same
 * identity system as everyone else and their actions on a session are attributable to a
 * real person rather than to "the coach". The link is nullable because a coach can be
 * listed and bookable before they have ever signed in.
 */
@Entity
@Table(name = "coach")
public class CoachEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_id", nullable = false, updatable = false)
    private String publicId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    private String headline;
    private String bio;

    @Column(name = "avatar_url")
    private String avatarUrl;

    /**
     * The zone their availability is declared in.
     *
     * <p>Stored beside the rules rather than assumed from the server, because the whole
     * point of {@code AvailabilityRule} holding local times is that they are local to
     * <i>someone</i>. A coach who moves countries changes this one field and their whole
     * schedule follows them.
     */
    @Column(nullable = false)
    private String timezone = "Asia/Kolkata";

    private String languages;

    /** Highest division or rank reached — the social proof on the coach card. */
    private String credentials;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    protected CoachEntity() {
    }

    public CoachEntity(String publicId, String displayName, String timezone) {
        this.publicId = publicId;
        this.displayName = displayName;
        this.timezone = timezone;
    }

    /**
     * The coach's zone, falling back to the business default.
     *
     * <p>Never throws. A malformed zone string is a data problem, and the sane response is
     * to generate slots in the default zone rather than to take the coaching page down.
     */
    public ZoneId zone() {
        try {
            return ZoneId.of(timezone);
        } catch (RuntimeException malformed) {
            return ZoneId.of("Asia/Kolkata");
        }
    }

    public Long getId() {
        return id;
    }

    public String getPublicId() {
        return publicId;
    }

    public Long getAccountId() {
        return accountId;
    }

    public void setAccountId(Long accountId) {
        this.accountId = accountId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getHeadline() {
        return headline;
    }

    public void setHeadline(String headline) {
        this.headline = headline;
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        this.bio = bio;
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        this.avatarUrl = avatarUrl;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getLanguages() {
        return languages;
    }

    public void setLanguages(String languages) {
        this.languages = languages;
    }

    public String getCredentials() {
        return credentials;
    }

    public void setCredentials(String credentials) {
        this.credentials = credentials;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(int sortOrder) {
        this.sortOrder = sortOrder;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }
}
