package com.atompay.cardpaycore.domain.enums;

/**
 * IN_PROGRESS rows have no cached response yet. Whether one is still being
 * worked on is never read from this column — it's whether some live
 * transaction holds the row lock (see PaymentService.handleIdempotentRequest).
 */
public enum IdempotencyStatus {
    IN_PROGRESS,
    COMPLETED
}
