package com.atompay.cardpaycore.dto;

import java.math.BigDecimal;

public class AuthorizeRequest {
    private String cardId;
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
