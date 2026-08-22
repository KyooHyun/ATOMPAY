CREATE TABLE audit_log (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    actor_username   VARCHAR(50)   NOT NULL,
    action           VARCHAR(30)   NOT NULL,
    authorization_id VARCHAR(36),
    card_id          VARCHAR(50),
    amount           DECIMAL(19,2),
    success          BOOLEAN       NOT NULL,
    failure_reason   VARCHAR(255),
    request_id       VARCHAR(64),
    created_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_log_authorization_id (authorization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
