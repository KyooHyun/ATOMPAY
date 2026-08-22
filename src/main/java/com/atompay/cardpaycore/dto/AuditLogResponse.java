package com.atompay.cardpaycore.dto;

import com.atompay.cardpaycore.domain.enums.TransactionType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class AuditLogResponse {
    private String actorUsername;
    private TransactionType action;
    private String authorizationId;
    private String cardId;
    private BigDecimal amount;
    private boolean success;
    private String failureReason;
    private String requestId;
    private OffsetDateTime createdAt;

    public AuditLogResponse() {
    }

    public AuditLogResponse(String actorUsername, TransactionType action, String authorizationId, String cardId,
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

    public String getActorUsername() {
        return actorUsername;
    }

    public void setActorUsername(String actorUsername) {
        this.actorUsername = actorUsername;
    }

    public TransactionType getAction() {
        return action;
    }

    public void setAction(TransactionType action) {
        this.action = action;
    }

    public String getAuthorizationId() {
        return authorizationId;
    }

    public void setAuthorizationId(String authorizationId) {
        this.authorizationId = authorizationId;
    }

    public String getCardId() {
        return cardId;
    }

    public void setCardId(String cardId) {
        this.cardId = cardId;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getFailureReason() {
        return failureReason;
    }

    public void setFailureReason(String failureReason) {
        this.failureReason = failureReason;
    }

    public String getRequestId() {
        return requestId;
    }

    public void setRequestId(String requestId) {
        this.requestId = requestId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
