package com.atompay.cardpaycore.service;

import com.atompay.cardpaycore.domain.entity.Authorization;
import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.domain.entity.IdempotencyKey;
import com.atompay.cardpaycore.domain.entity.PaymentTransaction;
import com.atompay.cardpaycore.domain.enums.AuthorizationStatus;
import com.atompay.cardpaycore.domain.enums.CardAccountStatus;
import com.atompay.cardpaycore.domain.enums.TransactionType;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.dto.PaymentResponse;
import com.atompay.cardpaycore.dto.PaymentTransactionResponse;
import com.atompay.cardpaycore.exception.BadRequestException;
import com.atompay.cardpaycore.exception.NotFoundException;
import com.atompay.cardpaycore.dto.AuditLogResponse;
import com.atompay.cardpaycore.repository.AuditLogRepository;
import com.atompay.cardpaycore.repository.AuthorizationRepository;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import com.atompay.cardpaycore.repository.IdempotencyKeyRepository;
import com.atompay.cardpaycore.repository.PaymentTransactionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final ObjectMapper objectMapper;
    private final CardAccountRepository cardAccountRepository;
    private final AuthorizationRepository authorizationRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;
    private final IdempotencyService idempotencyService;
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;

    public PaymentService(ObjectMapper objectMapper,
                          CardAccountRepository cardAccountRepository,
                          AuthorizationRepository authorizationRepository,
                          IdempotencyKeyRepository idempotencyKeyRepository,
                          PaymentTransactionRepository paymentTransactionRepository,
                          IdempotencyService idempotencyService,
                          AuditLogService auditLogService,
                          AuditLogRepository auditLogRepository) {
        this.objectMapper = objectMapper;
        this.cardAccountRepository = cardAccountRepository;
        this.authorizationRepository = authorizationRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
        this.idempotencyService = idempotencyService;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
    }

    @Transactional
    public PaymentResponse authorize(AuthorizeRequest request, String idempotencyKey) {
        String requestBodyHash = generateRequestBodyHash(request.getCardId(), request.getAmount());
        return handleIdempotentRequest(
                idempotencyKey,
                "/api/v1/payments/authorize",
                requestBodyHash,
                () -> createAuthorization(request)
        );
    }

    @Transactional
    public PaymentResponse capture(String authorizationId, BigDecimal amount, String idempotencyKey) {
        String requestBodyHash = generateRequestBodyHash(authorizationId, amount);
        return handleIdempotentRequest(
                idempotencyKey,
                "/api/v1/payments/" + authorizationId + "/capture",
                requestBodyHash,
                () -> doCapture(authorizationId, amount)
        );
    }

    @Transactional
    public PaymentResponse cancel(String authorizationId, String idempotencyKey) {
        String requestBodyHash = generateRequestBodyHash(authorizationId);
        return handleIdempotentRequest(
                idempotencyKey,
                "/api/v1/payments/" + authorizationId + "/cancel",
                requestBodyHash,
                () -> doCancel(authorizationId)
        );
    }

    @Transactional
    public PaymentResponse partialRefund(String authorizationId, BigDecimal amount, String idempotencyKey) {
        String requestBodyHash = generateRequestBodyHash(authorizationId, amount);
        return handleIdempotentRequest(
                idempotencyKey,
                "/api/v1/payments/" + authorizationId + "/partial-refund",
                requestBodyHash,
                () -> doPartialRefund(authorizationId, amount)
        );
    }

    @Transactional
    public PaymentResponse refund(String authorizationId, BigDecimal amount, String idempotencyKey) {
        String requestBodyHash = generateRequestBodyHash(authorizationId, amount);
        return handleIdempotentRequest(
                idempotencyKey,
                "/api/v1/payments/" + authorizationId + "/refund",
                requestBodyHash,
                () -> doRefund(authorizationId, amount)
        );
    }

    private PaymentResponse createAuthorization(AuthorizeRequest request) {
        try {
            CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(request.getCardId())
                    .orElseThrow(() -> new NotFoundException("Card account not found: " + request.getCardId()));

            if (cardAccount.getStatus() != CardAccountStatus.ACTIVE) {
                throw new BadRequestException("Card account is not active.");
            }

            if (cardAccount.getAvailableAmount().compareTo(request.getAmount()) < 0) {
                throw new BadRequestException("Available credit limit is insufficient.");
            }

            cardAccount.deductAvailableAmount(request.getAmount());
            cardAccountRepository.save(cardAccount);

            String authorizationId = UUID.randomUUID().toString();
            Authorization authorization = new Authorization(
                    authorizationId,
                    cardAccount.getCardId(),
                    request.getAmount(),
                    AuthorizationStatus.AUTHORIZED,
                    BigDecimal.ZERO,
                    OffsetDateTime.now(),
                    OffsetDateTime.now()
            );
            authorizationRepository.save(authorization);
            recordTransaction(authorization, TransactionType.AUTHORIZATION, authorization.getAmount());
            auditLogService.recordSuccess(TransactionType.AUTHORIZATION, authorizationId, cardAccount.getCardId(), authorization.getAmount());

            log.info("Authorization created: authorizationId={}, cardId={}, amount={}",
                    authorizationId, cardAccount.getCardId(), request.getAmount());

            return mapToResponse(authorization);
        } catch (RuntimeException ex) {
            auditLogService.recordFailure(TransactionType.AUTHORIZATION, null, request.getCardId(), request.getAmount(), ex.getMessage());
            throw ex;
        }
    }

    private PaymentResponse doCapture(String authorizationId, BigDecimal amount) {
        try {
            Authorization authorization = authorizationRepository.findByAuthorizationIdForUpdate(authorizationId)
                    .orElseThrow(() -> new NotFoundException("Authorization not found: " + authorizationId));

            authorization.capture(amount);
            authorizationRepository.save(authorization);
            recordTransaction(authorization, TransactionType.CAPTURE, amount);
            auditLogService.recordSuccess(TransactionType.CAPTURE, authorizationId, authorization.getCardId(), amount);

            log.info("Payment captured: authorizationId={}, amount={}", authorizationId, amount);

            return mapToResponse(authorization);
        } catch (RuntimeException ex) {
            auditLogService.recordFailure(TransactionType.CAPTURE, authorizationId, null, amount, ex.getMessage());
            throw ex;
        }
    }

    private PaymentResponse doCancel(String authorizationId) {
        try {
            Authorization authorization = authorizationRepository.findByAuthorizationIdForUpdate(authorizationId)
                    .orElseThrow(() -> new NotFoundException("Authorization not found: " + authorizationId));

            authorization.cancel();
            authorizationRepository.save(authorization);

            CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(authorization.getCardId())
                    .orElseThrow(() -> new NotFoundException("Card account not found: " + authorization.getCardId()));
            cardAccount.increaseAvailableAmount(authorization.getAmount());
            cardAccountRepository.save(cardAccount);
            recordTransaction(authorization, TransactionType.CANCEL, authorization.getAmount());
            auditLogService.recordSuccess(TransactionType.CANCEL, authorizationId, authorization.getCardId(), authorization.getAmount());

            log.info("Authorization cancelled: authorizationId={}, restoredAmount={}", authorizationId, authorization.getAmount());

            return mapToResponse(authorization);
        } catch (RuntimeException ex) {
            auditLogService.recordFailure(TransactionType.CANCEL, authorizationId, null, null, ex.getMessage());
            throw ex;
        }
    }

    private PaymentResponse doPartialRefund(String authorizationId, BigDecimal amount) {
        try {
            Authorization authorization = authorizationRepository.findByAuthorizationIdForUpdate(authorizationId)
                    .orElseThrow(() -> new NotFoundException("Authorization not found: " + authorizationId));

            authorization.partialRefund(amount);
            authorizationRepository.save(authorization);

            CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(authorization.getCardId())
                    .orElseThrow(() -> new NotFoundException("Card account not found: " + authorization.getCardId()));
            cardAccount.increaseAvailableAmount(amount);
            cardAccountRepository.save(cardAccount);
            recordTransaction(authorization, TransactionType.PARTIAL_REFUND, amount);
            auditLogService.recordSuccess(TransactionType.PARTIAL_REFUND, authorizationId, authorization.getCardId(), amount);

            log.info("Partial refund processed: authorizationId={}, amount={}, totalRefunded={}",
                    authorizationId, amount, authorization.getRefundedAmount());

            return mapToResponse(authorization);
        } catch (RuntimeException ex) {
            auditLogService.recordFailure(TransactionType.PARTIAL_REFUND, authorizationId, null, amount, ex.getMessage());
            throw ex;
        }
    }

    private PaymentResponse doRefund(String authorizationId, BigDecimal amount) {
        try {
            Authorization authorization = authorizationRepository.findByAuthorizationIdForUpdate(authorizationId)
                    .orElseThrow(() -> new NotFoundException("Authorization not found: " + authorizationId));

            boolean fullRefund = authorization.getRemainingRefundableAmount().compareTo(amount) == 0;
            authorization.refund(amount);
            authorizationRepository.save(authorization);

            CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(authorization.getCardId())
                    .orElseThrow(() -> new NotFoundException("Card account not found: " + authorization.getCardId()));
            cardAccount.increaseAvailableAmount(amount);
            cardAccountRepository.save(cardAccount);
            TransactionType refundAction = fullRefund ? TransactionType.REFUND : TransactionType.PARTIAL_REFUND;
            recordTransaction(authorization, refundAction, amount);
            auditLogService.recordSuccess(refundAction, authorizationId, authorization.getCardId(), amount);

            log.info("Refund processed: authorizationId={}, amount={}, full={}", authorizationId, amount, fullRefund);

            return mapToResponse(authorization);
        } catch (RuntimeException ex) {
            auditLogService.recordFailure(TransactionType.REFUND, authorizationId, null, amount, ex.getMessage());
            throw ex;
        }
    }

    private PaymentResponse mapToResponse(Authorization authorization) {
        return new PaymentResponse(
                authorization.getAuthorizationId(),
                authorization.getCardId(),
                authorization.getAmount(),
                authorization.getStatus().name(),
                authorization.getRefundedAmount(),
                authorization.getCreatedAt(),
                authorization.getUpdatedAt()
        );
    }

    /**
     * Idempotency flow:
     * 1. Check for existing cached response — return it immediately if found.
     * 2. Reserve a placeholder in its own REQUIRES_NEW transaction so a DB
     *    constraint violation never taints the outer EntityManager.
     * 3. Run the business operation inside the outer @Transactional.
     * 4. On success: update the placeholder with the response (same outer tx,
     *    so payment data and idempotency record commit atomically).
     * 5. On failure: delete the placeholder in a new REQUIRES_NEW transaction
     *    so the key is available for retries.
     */
    private PaymentResponse handleIdempotentRequest(String idempotencyKey,
                                                     String requestUri,
                                                     String requestBodyHash,
                                                     Supplier<PaymentResponse> operation) {
        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByKeyValue(idempotencyKey);
        if (existing.isPresent()) {
            return resolveExistingKey(existing.get(), requestUri, requestBodyHash);
        }

        boolean reserved = idempotencyService.tryReservePlaceholder(idempotencyKey, requestUri, requestBodyHash);
        if (!reserved) {
            IdempotencyKey concurrent = idempotencyKeyRepository.findByKeyValueForUpdate(idempotencyKey)
                    .orElseThrow(() -> new BadRequestException("Idempotency key is already being processed. Retry later."));
            return resolveExistingKey(concurrent, requestUri, requestBodyHash);
        }

        try {
            PaymentResponse response = operation.get();
            IdempotencyKey placeholder = idempotencyKeyRepository.findByKeyValueForUpdate(idempotencyKey)
                    .orElseThrow(() -> new IllegalStateException("Idempotency placeholder was unexpectedly removed"));
            placeholder.setResponsePayload(serializeResponse(response));
            idempotencyKeyRepository.save(placeholder);
            return response;
        } catch (RuntimeException ex) {
            try {
                idempotencyService.releaseOnFailure(idempotencyKey);
            } catch (Exception cleanupEx) {
                log.warn("Failed to release idempotency placeholder: key={}", idempotencyKey, cleanupEx);
            }
            throw ex;
        }
    }

    private PaymentResponse resolveExistingKey(IdempotencyKey key, String requestUri, String requestBodyHash) {
        if (!key.getRequestUri().equals(requestUri) || !key.getRequestBodyHash().equals(requestBodyHash)) {
            throw new BadRequestException("Idempotency key reuse with different request body is not allowed.");
        }
        if (key.getResponsePayload().isBlank()) {
            throw new BadRequestException("Idempotency key is already being processed. Retry later.");
        }
        log.debug("Returning cached idempotent response for key={}", key.getKeyValue());
        return deserializeResponse(key.getResponsePayload());
    }

    private String generateRequestBodyHash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                digest.update((value == null ? "" : value.toString()).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0); // NUL-byte separator prevents cross-field collisions
            }
            return Base64.getEncoder().encodeToString(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private void recordTransaction(Authorization authorization, TransactionType transactionType, BigDecimal amount) {
        paymentTransactionRepository.save(new PaymentTransaction(
                UUID.randomUUID().toString(),
                authorization.getAuthorizationId(),
                transactionType,
                amount,
                authorization.getStatus(),
                OffsetDateTime.now()
        ));
    }

    @Transactional(readOnly = true)
    public List<PaymentTransactionResponse> listTransactions(String authorizationId) {
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new BadRequestException("Authorization ID is required.");
        }
        return paymentTransactionRepository.findByAuthorizationIdOrderByCreatedAtAsc(authorizationId).stream()
                .map(this::mapToTransactionResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<AuditLogResponse> listAuditLog(String authorizationId) {
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new BadRequestException("Authorization ID is required.");
        }
        return auditLogRepository.findByAuthorizationIdOrderByCreatedAtAsc(authorizationId).stream()
                .map(entry -> new AuditLogResponse(
                        entry.getActorUsername(),
                        entry.getAction(),
                        entry.getAuthorizationId(),
                        entry.getCardId(),
                        entry.getAmount(),
                        entry.isSuccess(),
                        entry.getFailureReason(),
                        entry.getRequestId(),
                        entry.getCreatedAt()
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(String authorizationId) {
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new BadRequestException("Authorization ID is required.");
        }
        Authorization authorization = authorizationRepository.findByAuthorizationId(authorizationId)
                .orElseThrow(() -> new NotFoundException("Authorization not found: " + authorizationId));
        return mapToResponse(authorization);
    }

    private PaymentTransactionResponse mapToTransactionResponse(PaymentTransaction transaction) {
        return new PaymentTransactionResponse(
                transaction.getTransactionId(),
                transaction.getTransactionType(),
                transaction.getAmount(),
                transaction.getStatus(),
                transaction.getCreatedAt()
        );
    }

    private String serializeResponse(PaymentResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize idempotent response", ex);
        }
    }

    private PaymentResponse deserializeResponse(String payload) {
        try {
            return objectMapper.readValue(payload, PaymentResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize idempotent response", ex);
        }
    }
}
