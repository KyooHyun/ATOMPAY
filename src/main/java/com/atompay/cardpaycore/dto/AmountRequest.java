package com.atompay.cardpaycore.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public class AmountRequest {

    /**
     * Zero is only meaningful for capturing a $0 verification authorization;
     * cancel/refund still reject it via Authorization's own domain rules.
     */
    @NotNull(message = "amount is required")
    @PositiveOrZero(message = "amount must not be negative")
    private BigDecimal amount;

    public AmountRequest() {
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
}
