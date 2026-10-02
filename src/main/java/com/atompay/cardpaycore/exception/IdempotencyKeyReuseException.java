package com.atompay.cardpaycore.exception;

/**
 * The key was already used for a different request. Mapped to 422 rather
 * than 400 — the request itself is well-formed; it's the pairing with this
 * key that can never succeed (draft-ietf-httpapi-idempotency-key-header).
 */
public class IdempotencyKeyReuseException extends BadRequestException {
    public IdempotencyKeyReuseException(String message) {
        super(message);
    }
}
