package com.globalfutservice.coaching;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The booking settings an admin changes from the Coaching diary. Exactly one row (V30).
 */
@Entity
@Table(name = "coaching_settings")
public class CoachingSettingsEntity {

    /** Always 1: the table holds one row, enforced by a check constraint. */
    @Id
    private Short id;

    @Column(name = "min_notice_minutes", nullable = false)
    private int minNoticeMinutes;

    @Column(name = "buffer_minutes", nullable = false)
    private int bufferMinutes;

    @Column(name = "hold_minutes", nullable = false)
    private int holdMinutes;

    @Column(name = "single_session_minutes", nullable = false)
    private int singleSessionMinutes;

    @Column(name = "block_session_minutes", nullable = false)
    private int blockSessionMinutes;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private Long updatedBy;

    protected CoachingSettingsEntity() {
    }

    public void apply(int minNoticeMinutes, int bufferMinutes, int holdMinutes,
                      int singleSessionMinutes, int blockSessionMinutes,
                      Long adminId, Instant at) {
        this.minNoticeMinutes = minNoticeMinutes;
        this.bufferMinutes = bufferMinutes;
        this.holdMinutes = holdMinutes;
        this.singleSessionMinutes = singleSessionMinutes;
        this.blockSessionMinutes = blockSessionMinutes;
        this.updatedBy = adminId;
        this.updatedAt = at;
    }

    public int getMinNoticeMinutes() { return minNoticeMinutes; }
    public int getBufferMinutes() { return bufferMinutes; }
    public int getHoldMinutes() { return holdMinutes; }
    public int getSingleSessionMinutes() { return singleSessionMinutes; }
    public int getBlockSessionMinutes() { return blockSessionMinutes; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getUpdatedBy() { return updatedBy; }
}
