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
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    protected Authorization() {
    }

    public Authorization(String authorizationId, String cardId, BigDecimal amount, AuthorizationStatus status, OffsetDateTime createdAt, OffsetDateTime updatedAt) {
        this.authorizationId = authorizationId;
        this.cardId = cardId;
        this.amount = amount;
        this.status = status;
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

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(AuthorizationStatus status) {
        this.status = status;
        this.updatedAt = OffsetDateTime.now();
    }
}
