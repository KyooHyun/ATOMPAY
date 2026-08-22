CREATE TABLE audit_log (
    id               BIGINT        NOT NULL AUTO_INCREMENT,
    actor_username   VARCHAR(50)   NOT NULL,
    action           VARCHAR(30)   NOT NULL,
    authorization_id VARCHAR(36)   NOT NULL,
    card_id          VARCHAR(50)   NOT NULL,
    amount           DECIMAL(19,2) NOT NULL,
    request_id       VARCHAR(64),
    created_at       DATETIME(6)   NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_audit_log_authorization_id (authorization_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
