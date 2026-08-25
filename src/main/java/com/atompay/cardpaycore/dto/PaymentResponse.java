package com.atompay.cardpaycore.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class PaymentResponse {
    private String authorizationId;
    private String cardId;
    private BigDecimal amount;
    private String status;
    private BigDecimal refundedAmount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private String kind;
    private BigDecimal originalAmount;
    private BigDecimal discountAmount;
    private String discountReasonCode;

    public PaymentResponse() {
    }

    public PaymentResponse(String authorizationId, String cardId, BigDecimal amount, String status,
                           BigDecimal refundedAmount, OffsetDateTime createdAt, OffsetDateTime updatedAt,
                           String kind, BigDecimal originalAmount, BigDecimal discountAmount, String discountReasonCode) {
        this.authorizationId = authorizationId;
        this.cardId = cardId;
        this.amount = amount;
        this.status = status;
        this.refundedAmount = refundedAmount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.kind = kind;
        this.originalAmount = originalAmount;
        this.discountAmount = discountAmount;
        this.discountReasonCode = discountReasonCode;
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

    public String getKind() {
        return kind;
    }

    public void setKind(String kind) {
        this.kind = kind;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public void setOriginalAmount(BigDecimal originalAmount) {
        this.originalAmount = originalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public void setDiscountAmount(BigDecimal discountAmount) {
        this.discountAmount = discountAmount;
    }

    public String getDiscountReasonCode() {
        return discountReasonCode;
    }

    public void setDiscountReasonCode(String discountReasonCode) {
        this.discountReasonCode = discountReasonCode;
    }
}
