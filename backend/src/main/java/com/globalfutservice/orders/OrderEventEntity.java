package com.globalfutservice.orders;

import com.globalfutservice.domain.orders.Actor;
import com.globalfutservice.domain.orders.OrderStatus;
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
 * One immutable line in an order's history.
 *
 * <p>This table is three things at once: the timeline an operator reads, the evidence
 * pack attached to a chargeback representment, and the answer to "who moved this order
 * and when?". It costs one insert per transition and is worth an order of magnitude more
 * than that the first time a payment is disputed.
 */
@Entity
@Table(name = "order_event")
public class OrderEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private OrderStatus fromStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private OrderStatus toStatus;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false)
    private Actor actorType;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(name = "actor_label")
    private String actorLabel;

    private String reason;

    /**
     * Names a row the customer's page words in their own language, with {@code detail} as
     * its one value ("SETTLEMENT_PAID", "₹475.00"). Null for an ordinary status change.
     */
    private String kind;

    private String detail;

    /** Staff's alone -- an admin's note, a proof upload. Never shown to the customer (V47). */
    @Column(name = "staff_only", nullable = false)
    private boolean staffOnly;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    protected OrderEventEntity() {
    }

    public OrderEventEntity(Long orderId, OrderStatus fromStatus, OrderStatus toStatus,
                            Actor actorType, Long actorId, String actorLabel, String reason) {
        this.orderId = orderId;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.actorType = actorType;
        this.actorId = actorId;
        this.actorLabel = actorLabel;
        this.reason = reason;
    }

    public OrderEventEntity(Long orderId, OrderStatus fromStatus, OrderStatus toStatus,
                            Actor actorType, Long actorId, String actorLabel, String reason,
                            String kind, String detail, boolean staffOnly) {
        this(orderId, fromStatus, toStatus, actorType, actorId, actorLabel, reason);
        this.kind = kind;
        this.detail = detail;
        this.staffOnly = staffOnly;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public OrderStatus getFromStatus() {
        return fromStatus;
    }

    public OrderStatus getToStatus() {
        return toStatus;
    }

    public Actor getActorType() {
        return actorType;
    }

    public String getActorLabel() {
        return actorLabel;
    }

    public String getReason() {
        return reason;
    }

    public String getKind() {
        return kind;
    }

    public String getDetail() {
        return detail;
    }

    public boolean isStaffOnly() {
        return staffOnly;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
