package com.atompay.cardpaycore.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class PaymentStatusResponse {
    private String authorizationId;
    private String cardId;
    private BigDecimal amount;
    private String status;
    private BigDecimal refundedAmount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public PaymentStatusResponse() {
    }

    public PaymentStatusResponse(String authorizationId, String cardId, BigDecimal amount, String status, BigDecimal refundedAmount, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.authorizationId = authorizationId;
        this.cardId = cardId;
        this.amount = amount;
        this.status = status;
        this.refundedAmount = refundedAmount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
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

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public void setRefundedAmount(BigDecimal refundedAmount) {
        this.refundedAmount = refundedAmount;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(OffsetDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
