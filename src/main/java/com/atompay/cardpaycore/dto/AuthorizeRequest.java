package com.atompay.cardpaycore.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public class AuthorizeRequest {

    @NotBlank(message = "cardId is required")
    private String cardId;

    @NotNull(message = "amount is required")
    @Positive(message = "amount must be positive")
    @DecimalMax(value = "999999.99", message = "amount exceeds the maximum transaction threshold")
    private BigDecimal amount;

    public AuthorizeRequest() {
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
}
