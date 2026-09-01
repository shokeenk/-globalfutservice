package com.globalfutservice.loyalty;

import com.globalfutservice.domain.loyalty.PointsEntryType;
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

/**
 * One movement in a customer's points wallet.
 *
 * <p>Append-only. There is no balance column anywhere: the balance is the sum of these
 * rows. That costs one aggregate query and buys the ability to answer "why is my balance
 * 80?" with a statement rather than an apology.
 */
@Entity
@Table(name = "points_ledger")
public class PointsLedgerEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "account_id", nullable = false)
    private Long accountId;

    @Column(name = "order_id")
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private PointsEntryType entryType;

    /** Signed. Positive grants, negative spends — the sign comes from the entry type. */
    @Column(nullable = false)
    private long amount;

    private String description;

    @Column(name = "actor_id")
    private Long actorId;

    /**
     * Which day a DAILY_BONUS entry claims. Null for every other entry type.
     *
     * <p>Carrying the date as a column rather than deriving it from {@code created_at} is
     * what lets a partial unique index enforce one claim per account per day in the
     * database. Deriving it would mean the check lives in application code, and "did they
     * already claim today" is exactly the sort of read-then-write that two taps on a phone
     * with a flaky connection will race.
     */
    @Column(name = "claim_date")
    private LocalDate claimDate;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected PointsLedgerEntity() {
    }

    public PointsLedgerEntity(Long accountId, Long orderId, PointsEntryType entryType,
                              long signedAmount, String description, Long actorId) {
        this.accountId = accountId;
        this.orderId = orderId;
        this.entryType = entryType;
        this.amount = signedAmount;
        this.description = description;
        this.actorId = actorId;
    }

    /** Daily check-in grant, keyed to the day it claims. */
    public static PointsLedgerEntity dailyBonus(Long accountId, long points, LocalDate claimDate) {
        PointsLedgerEntity entry = new PointsLedgerEntity(
                accountId, null, PointsEntryType.DAILY_BONUS, points,
                "Daily check-in " + claimDate, null);
        entry.claimDate = claimDate;
        return entry;
    }

    public Long getId() {
        return id;
    }

    public Long getAccountId() {
        return accountId;
    }

    public Long getOrderId() {
        return orderId;
    }

    public PointsEntryType getEntryType() {
        return entryType;
    }

    public long getAmount() {
        return amount;
    }

    public String getDescription() {
        return description;
    }

    public LocalDate getClaimDate() {
        return claimDate;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
