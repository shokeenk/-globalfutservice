package com.globalfutservice.payments.payop;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One change to the Payop fee table: what the method was before, what it became, and who did it. */
@Entity
@Table(name = "payop_fee_audit")
public class PayopFeeAuditEntity {

    public static final String IMPORTED = "IMPORTED";
    public static final String UPDATED = "UPDATED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "method_id", nullable = false)
    private Long methodId;

    @Column(nullable = false)
    private int version;

    @Column(nullable = false)
    private String action;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "before_json", columnDefinition = "jsonb")
    private String beforeJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "after_json", nullable = false, columnDefinition = "jsonb")
    private String afterJson;

    @Column(name = "actor_id")
    private Long actorId;

    @Column(nullable = false)
    private Instant at;

    protected PayopFeeAuditEntity() {
        // JPA
    }

    public PayopFeeAuditEntity(long methodId, int version, String action, String beforeJson, String afterJson,
                               Long actorId, Instant at) {
        this.methodId = methodId;
        this.version = version;
        this.action = action;
        this.beforeJson = beforeJson;
        this.afterJson = afterJson;
        this.actorId = actorId;
        this.at = at;
    }

    public Long getId() {
        return id;
    }

    public Long getMethodId() {
        return methodId;
    }

    public int getVersion() {
        return version;
    }

    public String getAction() {
        return action;
    }

    public String getBeforeJson() {
        return beforeJson;
    }

    public String getAfterJson() {
        return afterJson;
    }

    public Long getActorId() {
        return actorId;
    }

    public Instant getAt() {
        return at;
    }
}
