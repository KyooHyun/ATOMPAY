package com.atompay.cardpaycore.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public class AuthorizeRequest {

    @NotBlank(message = "cardId is required")
    private String cardId;

    /**
     * Zero is a valid authorization amount — card networks use $0 authorizations
     * to verify a card (status, existence) without placing a hold, e.g. for
     * tokenization or account-verification flows. See {@link com.atompay.cardpaycore.service.PaymentService}.
     */
    @NotNull(message = "amount is required")
    @PositiveOrZero(message = "amount must not be negative")
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
