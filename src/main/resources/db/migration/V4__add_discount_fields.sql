ALTER TABLE authorization_record
    ADD COLUMN kind                 VARCHAR(20)   NOT NULL DEFAULT 'STANDARD',
    ADD COLUMN original_amount      DECIMAL(19,2) NULL,
    ADD COLUMN discount_amount      DECIMAL(19,2) NULL,
    ADD COLUMN discount_reason_code VARCHAR(50)   NULL;

ALTER TABLE payment_transaction
    ADD COLUMN original_amount      DECIMAL(19,2) NULL,
    ADD COLUMN discount_amount      DECIMAL(19,2) NULL,
    ADD COLUMN discount_reason_code VARCHAR(50)   NULL;
