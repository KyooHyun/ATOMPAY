package com.atompay.cardpaycore.domain.entity;

import com.atompay.cardpaycore.domain.enums.CardAccountStatus;
import jakarta.persistence.*;
import java.math.BigDecimal;

@Entity
@Table(name = "card_account")
public class CardAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String cardId;

    @Column(nullable = false)
    private String cardNumber;

    @Column(nullable = false)
    private BigDecimal creditLimit;

    @Column(nullable = false)
    private BigDecimal availableAmount;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private CardAccountStatus status;

    protected CardAccount() {
    }

    public CardAccount(String cardId, String cardNumber, BigDecimal creditLimit, BigDecimal availableAmount, CardAccountStatus status) {
        this.cardId = cardId;
        this.cardNumber = cardNumber;
        this.creditLimit = creditLimit;
        this.availableAmount = availableAmount;
        this.status = status;
    }

    public Long getId() {
        return id;
    }

    public String getCardId() {
        return cardId;
    }

    public String getCardNumber() {
        return cardNumber;
    }

    public BigDecimal getCreditLimit() {
        return creditLimit;
    }

    public BigDecimal getAvailableAmount() {
        return availableAmount;
    }

    public CardAccountStatus getStatus() {
        return status;
    }

    public void deductAvailableAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        BigDecimal updatedAmount = this.availableAmount.subtract(amount);
        if (updatedAmount.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Available amount cannot become negative.");
        }
        this.availableAmount = updatedAmount;
    }

    public void increaseAvailableAmount(BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive.");
        }
        this.availableAmount = this.availableAmount.add(amount);
    }
}
