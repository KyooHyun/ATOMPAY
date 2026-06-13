package com.atompay.cardpaycore.domain.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "idempotency_key")
public class IdempotencyKey {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String keyValue;

    @Column(nullable = false)
    private String requestUri;

    @Column(nullable = false)
    private String requestBodyHash;

    @Lob
    @Column(nullable = false)
    private String responsePayload;

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    protected IdempotencyKey() {
    }

    public IdempotencyKey(String keyValue, String requestUri, String requestBodyHash, String responsePayload, OffsetDateTime createdAt) {
        this.keyValue = keyValue;
        this.requestUri = requestUri;
        this.requestBodyHash = requestBodyHash;
        this.responsePayload = responsePayload;
        this.createdAt = createdAt;
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

    public String getResponsePayload() {
        return responsePayload;
    }
}
