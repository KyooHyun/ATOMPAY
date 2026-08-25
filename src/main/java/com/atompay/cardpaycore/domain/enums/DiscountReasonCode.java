package com.atompay.cardpaycore.domain.enums;

/**
 * Valid reason codes a merchant may submit for a discount that waives an
 * authorization's charge amount. The payment core validates the code is
 * known and that the arithmetic is consistent — eligibility itself is the
 * merchant's responsibility, not the payment core's.
 */
public enum DiscountReasonCode {
    NATIONAL_MERIT_RECIPIENT
}
