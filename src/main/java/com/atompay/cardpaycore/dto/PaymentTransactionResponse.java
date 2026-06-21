package com.atompay.cardpaycore.dto;

import com.atompay.cardpaycore.domain.enums.AuthorizationStatus;
import com.atompay.cardpaycore.domain.enums.TransactionType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public class PaymentTransactionResponse {
    private String transactionId;
    private TransactionType transactionType;
    private BigDecimal amount;
    private AuthorizationStatus status;
    private OffsetDateTime createdAt;

    public PaymentTransactionResponse() {
    }

    public PaymentTransactionResponse(String transactionId,
                                      TransactionType transactionType,
                                      BigDecimal amount,
                                      AuthorizationStatus status,
                                      OffsetDateTime createdAt) {
        this.transactionId = transactionId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public void setTransactionId(String transactionId) {
        this.transactionId = transactionId;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public void setTransactionType(TransactionType transactionType) {
        this.transactionType = transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }

    public AuthorizationStatus getStatus() {
        return status;
    }

    public void setStatus(AuthorizationStatus status) {
        this.status = status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
