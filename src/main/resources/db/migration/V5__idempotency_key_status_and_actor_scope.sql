-- Idempotency keys are client-generated, so two callers can pick the same
-- one. Scope uniqueness to the caller, the way Stripe scopes keys per
-- account: otherwise one caller's retry could replay another's response.
ALTER TABLE idempotency_key
    ADD COLUMN actor  VARCHAR(100) NOT NULL DEFAULT 'system' AFTER id,
    ADD COLUMN status VARCHAR(20)  NOT NULL DEFAULT 'COMPLETED' AFTER request_body_hash;

-- Before V5 an in-flight (or abandoned) placeholder was an empty payload.
UPDATE idempotency_key SET status = 'IN_PROGRESS' WHERE response_payload = '';

ALTER TABLE idempotency_key
    DROP INDEX uk_idempotency_key_value,
    ADD UNIQUE KEY uk_idempotency_actor_key (actor, key_value);
