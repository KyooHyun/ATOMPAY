package com.atompay.cardpaycore.domain.entity;

import com.atompay.cardpaycore.domain.enums.TransactionType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "audit_log")
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String actorUsername;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private TransactionType action;

    /** Null for a failed authorize attempt -- no authorization exists yet to reference. */
    private String authorizationId;

    /** Null when the failure happened before the card account was resolved. */
    private String cardId;

    /** Null when the failure happened before an amount could be attributed (e.g. authorization not found). */
    private BigDecimal amount;

    @Column(nullable = false)
    private boolean success;

    private String failureReason;

    private String requestId;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected AuditLog() {
    }

    public AuditLog(String actorUsername, TransactionType action, String authorizationId, String cardId,
                     BigDecimal amount, boolean success, String failureReason, String requestId, OffsetDateTime createdAt) {
        this.actorUsername = actorUsername;
        this.action = action;
        this.authorizationId = authorizationId;
        this.cardId = cardId;
        this.amount = amount;
        this.success = success;
        this.failureReason = failureReason;
        this.requestId = requestId;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public String getActorUsername() {
        return actorUsername;
    }

    public TransactionType getAction() {
        return action;
    }

    public String getAuthorizationId() {
        return authorizationId;
    }

    public String getCardId() {
        return cardId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public String getRequestId() {
        return requestId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
