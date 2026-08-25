package com.atompay.cardpaycore.domain.entity;

import com.atompay.cardpaycore.domain.enums.AuthorizationStatus;
import com.atompay.cardpaycore.domain.enums.TransactionType;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Entity
@Table(name = "payment_transaction")
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String transactionId;

    @Column(nullable = false)
    private String authorizationId;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private TransactionType transactionType;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private AuthorizationStatus status;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    /** Pre-discount amount for a DISCOUNTED_ZERO authorization's AUTHORIZATION row; null otherwise. */
    @Column
    private BigDecimal originalAmount;

    /** Amount waived for a DISCOUNTED_ZERO authorization's AUTHORIZATION row; null otherwise. */
    @Column
    private BigDecimal discountAmount;

    /** Discount reason code for a DISCOUNTED_ZERO authorization's AUTHORIZATION row; null otherwise. */
    @Column
    private String discountReasonCode;

    protected PaymentTransaction() {
    }

    public PaymentTransaction(String transactionId, String authorizationId, TransactionType transactionType, BigDecimal amount, AuthorizationStatus status, OffsetDateTime createdAt) {
        this(transactionId, authorizationId, transactionType, amount, status, createdAt, null, null, null);
    }

    public PaymentTransaction(String transactionId, String authorizationId, TransactionType transactionType,
                               BigDecimal amount, AuthorizationStatus status, OffsetDateTime createdAt,
                               BigDecimal originalAmount, BigDecimal discountAmount, String discountReasonCode) {
        this.transactionId = transactionId;
        this.authorizationId = authorizationId;
        this.transactionType = transactionType;
        this.amount = amount;
        this.status = status;
        this.createdAt = createdAt;
        this.originalAmount = originalAmount;
        this.discountAmount = discountAmount;
        this.discountReasonCode = discountReasonCode;
    }

    public Long getId() {
        return id;
    }

    public String getTransactionId() {
        return transactionId;
    }

    public String getAuthorizationId() {
        return authorizationId;
    }

    public TransactionType getTransactionType() {
        return transactionType;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public AuthorizationStatus getStatus() {
        return status;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public BigDecimal getOriginalAmount() {
        return originalAmount;
    }

    public BigDecimal getDiscountAmount() {
        return discountAmount;
    }

    public String getDiscountReasonCode() {
        return discountReasonCode;
    }
}
