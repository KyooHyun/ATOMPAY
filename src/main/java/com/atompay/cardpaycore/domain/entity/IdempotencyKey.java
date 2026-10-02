package com.atompay.cardpaycore.domain.entity;

import com.atompay.cardpaycore.domain.enums.IdempotencyStatus;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.OffsetDateTime;

@Entity
@Table(name = "idempotency_key",
        uniqueConstraints = @UniqueConstraint(name = "uk_idempotency_actor_key", columnNames = {"actor", "key_value"}))
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String actor;

    @Column(nullable = false)
    private String keyValue;

    @Column(nullable = false)
    private String requestUri;

    @Column(nullable = false)
    private String requestBodyHash;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    @JdbcTypeCode(SqlTypes.VARCHAR)
    private IdempotencyStatus status;

    @Lob
    @Column(nullable = false)
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String responsePayload;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected IdempotencyKey() {
    }

    public static IdempotencyKey placeholder(String actor, String keyValue, String requestUri, String requestBodyHash) {
        IdempotencyKey key = new IdempotencyKey();
        key.actor = actor;
        key.keyValue = keyValue;
        key.requestUri = requestUri;
        key.requestBodyHash = requestBodyHash;
        key.status = IdempotencyStatus.IN_PROGRESS;
        key.responsePayload = "";
        key.createdAt = OffsetDateTime.now();
        return key;
    }

    public String getActor() {
        return actor;
    }

    public String getKeyValue() {
        return keyValue;
    }

    public String getRequestUri() {
        return requestUri;
    }

    public String getRequestBodyHash() {
        return requestBodyHash;
    }

    public IdempotencyStatus getStatus() {
        return status;
    }

    public String getResponsePayload() {
        return responsePayload;
    }

    public boolean isCompleted() {
        return status == IdempotencyStatus.COMPLETED;
    }

    public boolean matches(String requestUri, String requestBodyHash) {
        return this.requestUri.equals(requestUri) && this.requestBodyHash.equals(requestBodyHash);
    }

    /**
     * Claims an IN_PROGRESS row whose previous owner's transaction ended
     * without completing it. That attempt left no business effect behind
     * (it rolled back), so it doesn't bind the key to its request either —
     * same as when a failed attempt used to delete its placeholder.
     */
    public void takeOver(String requestUri, String requestBodyHash) {
        if (isCompleted()) {
            throw new IllegalStateException("Completed idempotency key cannot be taken over.");
        }
        this.requestUri = requestUri;
        this.requestBodyHash = requestBodyHash;
    }

    public void complete(String responsePayload) {
        this.responsePayload = responsePayload;
        this.status = IdempotencyStatus.COMPLETED;
    }
}
