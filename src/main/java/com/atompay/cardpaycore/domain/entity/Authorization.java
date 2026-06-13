package com.atompay.cardpaycore.domain.entity;

import com.atompay.cardpaycore.domain.enums.AuthorizationStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "authorization_record")
public class Authorization {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String authorizationId;

    @Column(nullable = false)
    private String cardId;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AuthorizationStatus status;

    @Column(nullable = false)
    private BigDecimal refundedAmount;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Authorization() {
    }

    public Authorization(String authorizationId, String cardId, BigDecimal amount, AuthorizationStatus status, BigDecimal refundedAmount, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.authorizationId = authorizationId;
        this.cardId = cardId;
        this.amount = amount;
        this.status = status;
        this.refundedAmount = refundedAmount;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public Long getId() {
        return id;
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

    public AuthorizationStatus getStatus() {
        return status;
    }

    public BigDecimal getRefundedAmount() {
        return refundedAmount;
    }

    public BigDecimal getRemainingRefundableAmount() {
        return amount.subtract(refundedAmount);
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void capture(BigDecimal captureAmount) {
        if (captureAmount == null || captureAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Capture amount must be positive.");
        }
        if (status != AuthorizationStatus.AUTHORIZED) {
            throw new IllegalStateException("Only authorized payments can be captured.");
        }
        if (captureAmount.compareTo(amount) != 0) {
            throw new IllegalArgumentException("Capture amount must equal the authorized amount.");
        }
        this.status = AuthorizationStatus.CAPTURED;
        this.updatedAt = OffsetDateTime.now();
    }

    public void cancel() {
        if (status != AuthorizationStatus.AUTHORIZED) {
            throw new IllegalStateException("Only authorized payments can be cancelled before capture.");
        }
        this.status = AuthorizationStatus.CANCELLED;
        this.updatedAt = OffsetDateTime.now();
    }

    public void partialRefund(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive.");
        }
        if (status != AuthorizationStatus.CAPTURED && status != AuthorizationStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("Only captured payments can be partially refunded.");
        }
        if (amount.compareTo(getRemainingRefundableAmount()) >= 0) {
            throw new IllegalArgumentException("Partial refund amount must be less than the remaining refundable amount.");
        }
        addRefundedAmount(amount);
        this.status = AuthorizationStatus.PARTIALLY_REFUNDED;
        this.updatedAt = OffsetDateTime.now();
    }

    public void refund(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive.");
        }
        if (status != AuthorizationStatus.CAPTURED && status != AuthorizationStatus.PARTIALLY_REFUNDED) {
            throw new IllegalStateException("Only captured or partially refunded payments can be refunded.");
        }
        if (amount.compareTo(getRemainingRefundableAmount()) > 0) {
            throw new IllegalArgumentException("Refund amount cannot exceed the remaining refundable amount.");
        }
        boolean fullRefund = amount.compareTo(getRemainingRefundableAmount()) == 0;
        addRefundedAmount(amount);
        this.status = fullRefund ? AuthorizationStatus.REFUNDED : AuthorizationStatus.PARTIALLY_REFUNDED;
        this.updatedAt = OffsetDateTime.now();
    }

    public void setStatus(AuthorizationStatus status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }

    public void addRefundedAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Refund amount must be positive.");
        }
        if (this.refundedAmount.add(amount).compareTo(this.amount) > 0) {
            throw new IllegalArgumentException("Refunded amount cannot exceed authorized amount.");
        }
        this.refundedAmount = this.refundedAmount.add(amount);
        this.updatedAt = OffsetDateTime.now();
    }
}
