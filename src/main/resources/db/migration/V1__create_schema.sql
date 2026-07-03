CREATE TABLE card_account (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    card_id          VARCHAR(50)  NOT NULL,
    card_number      VARCHAR(50)  NOT NULL,
    credit_limit     DECIMAL(19,2) NOT NULL,
    available_amount DECIMAL(19,2) NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_card_account_card_id (card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE authorization_record (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    authorization_id VARCHAR(36)   NOT NULL,
    card_id          VARCHAR(50)   NOT NULL,
    amount           DECIMAL(19,2) NOT NULL,
    status           VARCHAR(30)   NOT NULL,
    refunded_amount  DECIMAL(19,2) NOT NULL DEFAULT 0.00,
    created_at       DATETIME(6)   NOT NULL,
    updated_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_authorization_id (authorization_id),
    INDEX idx_authorization_card_id (card_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE payment_transaction (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    transaction_id   VARCHAR(36)   NOT NULL,
    authorization_id VARCHAR(36)   NOT NULL,
    transaction_type VARCHAR(30)   NOT NULL,
    amount           DECIMAL(19,2) NOT NULL,
    status           VARCHAR(30)   NOT NULL,
    created_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_transaction_id (transaction_id),
    INDEX idx_payment_transaction_auth_id (authorization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE idempotency_key (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    key_value         VARCHAR(64)  NOT NULL,
    request_uri       VARCHAR(255) NOT NULL,
    request_body_hash VARCHAR(64)  NOT NULL,
    response_payload  LONGTEXT     NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency_key_value (key_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
