package com.atompay.cardpaycore.domain.entity;

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
    private String status;

    protected CardAccount() {
    }

    public CardAccount(String cardId, String cardNumber, BigDecimal creditLimit, BigDecimal availableAmount, String status) {
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

    public String getStatus() {
        return status;
    }

    public void deductAvailableAmount(BigDecimal amount) {
        this.availableAmount = this.availableAmount.subtract(amount);
    }

    public void increaseAvailableAmount(BigDecimal amount) {
        this.availableAmount = this.availableAmount.add(amount);
    }
}
