package com.atompay.cardpaycore.domain.enums;

/**
 * Distinguishes the two kinds of $0-charge authorization the core handles —
 * they must not be treated as the same case even though both charge $0.
 */
public enum AuthorizationKind {
    /** Normal authorization: a positive charge amount. */
    STANDARD,
    /** Card-validity check only (tokenization/account verification) — not a real transaction. */
    VERIFICATION,
    /** A real transaction whose original amount was fully waived by a merchant-submitted discount. */
    DISCOUNTED_ZERO
}
