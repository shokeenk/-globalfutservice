package com.globalfutservice.credentials;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The sealed EA sign-in for one comfort-trade order.
 *
 * <p>Everything about this table is shaped by the assumption that it will one day be
 * read by someone who should not have it:
 * <ul>
 *   <li>Contents are envelope-encrypted with a per-row data key; the master key is not in
 *       the database, so a stolen dump is inert.</li>
 *   <li>{@code purgeAfter} is set at write time, and a scheduled job enforces it whatever
 *       the order's state — belt as well as braces.</li>
 *   <li>{@code accessedCount} and {@code lastAccessedBy} exist because the operators are
 *       part of the threat model too.</li>
 * </ul>
 */
@Entity
@Table(name = "credential_vault")
public class CredentialVaultEntity {

    @Id
    @Column(name = "order_id")
    private Long orderId;

    private byte[] ciphertext;

    private byte[] iv;

    @Column(name = "wrapped_dek")
    private byte[] wrappedDek;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "purge_after", nullable = false)
    private Instant purgeAfter;

    @Column(name = "purged_at")
    private Instant purgedAt;

    @Column(name = "accessed_count", nullable = false)
    private int accessedCount;

    @Column(name = "last_accessed_at")
    private Instant lastAccessedAt;

    @Column(name = "last_accessed_by")
    private Long lastAccessedBy;

    protected CredentialVaultEntity() {
    }

    public CredentialVaultEntity(Long orderId, byte[] ciphertext, byte[] iv,
                                 byte[] wrappedDek, Instant purgeAfter) {
        this.orderId = orderId;
        this.ciphertext = ciphertext;
        this.iv = iv;
        this.wrappedDek = wrappedDek;
        this.purgeAfter = purgeAfter;
    }

    public boolean isPurged() {
        return purgedAt != null || ciphertext == null;
    }

    /** Irreversibly removes key material. There is no undo, by design. */
    public void purge(Instant at) {
        this.ciphertext = null;
        this.iv = null;
        this.wrappedDek = null;
        this.purgedAt = at;
    }

    public void recordAccess(Long operatorId, Instant at) {
        this.accessedCount++;
        this.lastAccessedAt = at;
        this.lastAccessedBy = operatorId;
    }

    public void extendRetention(Instant purgeAfter) {
        this.purgeAfter = purgeAfter;
    }

    public Long getOrderId() {
        return orderId;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    public byte[] getIv() {
        return iv;
    }

    public byte[] getWrappedDek() {
        return wrappedDek;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPurgeAfter() {
        return purgeAfter;
    }

    public Instant getPurgedAt() {
        return purgedAt;
    }

    public int getAccessedCount() {
        return accessedCount;
    }

    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    public Long getLastAccessedBy() {
        return lastAccessedBy;
    }

    /** Must never render its contents, in any log, at any level. */
    @Override
    public String toString() {
        return "CredentialVault[order=" + orderId + ", purged=" + isPurged() + "]";
    }
}
