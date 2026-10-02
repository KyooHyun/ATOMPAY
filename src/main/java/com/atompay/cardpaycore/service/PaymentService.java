package com.atompay.cardpaycore.service;

import com.atompay.cardpaycore.domain.entity.Authorization;
import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.domain.entity.IdempotencyKey;
import com.atompay.cardpaycore.domain.entity.PaymentTransaction;
import com.atompay.cardpaycore.domain.enums.AuthorizationKind;
import com.atompay.cardpaycore.domain.enums.AuthorizationStatus;
import com.atompay.cardpaycore.domain.enums.CardAccountStatus;
import com.atompay.cardpaycore.domain.enums.DiscountReasonCode;
import com.atompay.cardpaycore.domain.enums.TransactionType;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.dto.PaymentResponse;
import com.atompay.cardpaycore.dto.PaymentTransactionResponse;
import com.atompay.cardpaycore.exception.BadRequestException;
import com.atompay.cardpaycore.exception.IdempotencyKeyReuseException;
import com.atompay.cardpaycore.exception.NotFoundException;
import com.atompay.cardpaycore.dto.AuditLogResponse;
import com.atompay.cardpaycore.repository.AuditLogRepository;
import com.atompay.cardpaycore.repository.AuthorizationRepository;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import com.atompay.cardpaycore.repository.IdempotencyKeyRepository;
import com.atompay.cardpaycore.repository.PaymentTransactionRepository;
import com.atompay.cardpaycore.security.CurrentActor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

    /** Matches idempotency_key.key_value VARCHAR(64); longer keys would fail at insert as a 500. */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

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
        String requestBodyHash = generateRequestBodyHash(request.getCardId(), request.getAmount(),
                request.getOriginalAmount(), request.getDiscountAmount(), request.getDiscountReasonCode());
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
            AuthorizationKind kind = validateAndResolveKind(request);

            CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(request.getCardId())
                    .orElseThrow(() -> new NotFoundException("Card account not found: " + request.getCardId()));

            if (cardAccount.getStatus() != CardAccountStatus.ACTIVE) {
                throw new BadRequestException("Card account is not active.");
            }

            boolean isZeroCharge = request.getAmount().compareTo(BigDecimal.ZERO) == 0;
            if (!isZeroCharge) {
                if (cardAccount.getAvailableAmount().compareTo(request.getAmount()) < 0) {
                    throw new BadRequestException("Available credit limit is insufficient.");
                }
                cardAccount.deductAvailableAmount(request.getAmount());
                cardAccountRepository.save(cardAccount);
            }

            String authorizationId = UUID.randomUUID().toString();
            Authorization authorization = new Authorization(
                    authorizationId,
                    cardAccount.getCardId(),
                    request.getAmount(),
                    AuthorizationStatus.AUTHORIZED,
                    BigDecimal.ZERO,
                    OffsetDateTime.now(),
                    OffsetDateTime.now(),
                    kind,
                    request.getOriginalAmount(),
                    request.getDiscountAmount(),
                    request.getDiscountReasonCode()
            );
            authorizationRepository.save(authorization);
            recordTransaction(authorization, TransactionType.AUTHORIZATION, authorization.getAmount());
            auditLogService.recordSuccess(TransactionType.AUTHORIZATION, authorizationId, cardAccount.getCardId(), authorization.getAmount());

            log.info("Authorization created: authorizationId={}, cardId={}, amount={}, kind={}",
                    authorizationId, cardAccount.getCardId(), request.getAmount(), kind);

            return mapToResponse(authorization);
        } catch (RuntimeException ex) {
            auditLogService.recordFailure(TransactionType.AUTHORIZATION, null, request.getCardId(), request.getAmount(), ex.getMessage());
            throw ex;
        }
    }

    /**
     * Discount eligibility (e.g. is this cardholder actually a national merit
     * recipient) is the merchant's responsibility, not the payment core's —
     * this only checks that a merchant-submitted discount is internally
     * consistent: all three fields present together, the discount doesn't
     * exceed the original amount, the arithmetic matches the charged amount,
     * and the reason code is one this core recognizes.
     */
    private AuthorizationKind validateAndResolveKind(AuthorizeRequest request) {
        boolean hasDiscountFields = request.getOriginalAmount() != null
                || request.getDiscountAmount() != null
                || request.getDiscountReasonCode() != null;
        if (!hasDiscountFields) {
            return request.getAmount().compareTo(BigDecimal.ZERO) == 0 ? AuthorizationKind.VERIFICATION : AuthorizationKind.STANDARD;
        }
        if (request.getOriginalAmount() == null || request.getDiscountAmount() == null
                || request.getDiscountReasonCode() == null || request.getDiscountReasonCode().isBlank()) {
            throw new BadRequestException("originalAmount, discountAmount, and discountReasonCode must all be provided together.");
        }
        if (request.getDiscountAmount().compareTo(BigDecimal.ZERO) < 0) {
            throw new BadRequestException("discountAmount must not be negative.");
        }
        if (request.getDiscountAmount().compareTo(request.getOriginalAmount()) > 0) {
            throw new BadRequestException("discountAmount cannot exceed originalAmount.");
        }
        if (request.getOriginalAmount().subtract(request.getDiscountAmount()).compareTo(request.getAmount()) != 0) {
            throw new BadRequestException("originalAmount - discountAmount must equal amount.");
        }
        try {
            DiscountReasonCode.valueOf(request.getDiscountReasonCode());
        } catch (IllegalArgumentException ex) {
            throw new BadRequestException("Unknown discountReasonCode: " + request.getDiscountReasonCode());
        }
        return request.getAmount().compareTo(BigDecimal.ZERO) == 0 ? AuthorizationKind.DISCOUNTED_ZERO : AuthorizationKind.STANDARD;
    }

    private PaymentResponse doCapture(String authorizationId, BigDecimal amount) {
        try {
            Authorization authorization = authorizationRepository.findByAuthorizationIdForUpdate(authorizationId)
                    .orElseThrow(() -> new NotFoundException("Authorization not found: " + authorizationId));

            BigDecimal releasedAmount = authorization.capture(amount);
            authorizationRepository.save(authorization);

            if (releasedAmount.compareTo(BigDecimal.ZERO) > 0) {
                CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(authorization.getCardId())
                        .orElseThrow(() -> new NotFoundException("Card account not found: " + authorization.getCardId()));
                cardAccount.increaseAvailableAmount(releasedAmount);
                cardAccountRepository.save(cardAccount);
            }
            recordTransaction(authorization, TransactionType.CAPTURE, amount);
            auditLogService.recordSuccess(TransactionType.CAPTURE, authorizationId, authorization.getCardId(), amount);

            log.info("Payment captured: authorizationId={}, amount={}, releasedRemainder={}", authorizationId, amount, releasedAmount);

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

            if (authorization.getAmount().compareTo(BigDecimal.ZERO) > 0) {
                CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(authorization.getCardId())
                        .orElseThrow(() -> new NotFoundException("Card account not found: " + authorization.getCardId()));
                cardAccount.increaseAvailableAmount(authorization.getAmount());
                cardAccountRepository.save(cardAccount);
            }
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

            if (amount.compareTo(BigDecimal.ZERO) > 0) {
                CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(authorization.getCardId())
                        .orElseThrow(() -> new NotFoundException("Card account not found: " + authorization.getCardId()));
                cardAccount.increaseAvailableAmount(amount);
                cardAccountRepository.save(cardAccount);
            }
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
                authorization.getUpdatedAt(),
                authorization.getKind().name(),
                authorization.getOriginalAmount(),
                authorization.getDiscountAmount(),
                authorization.getDiscountReasonCode()
        );
    }

    /**
     * Idempotency flow. The row lock on the key — not a status column, not
     * a timeout — is what says "someone is working on this right now":
     *
     * 1. Fast path: a COMPLETED key replays its cached response.
     * 2. Otherwise make sure a row exists: reserve an IN_PROGRESS placeholder
     *    in its own committed REQUIRES_NEW transaction. Losing that insert
     *    race to a concurrent duplicate is fine — the row exists either way.
     * 3. Lock the row (SELECT ... FOR UPDATE) in the outer transaction. If
     *    another request owns it, this blocks until that request commits or
     *    rolls back.
     * 4. With the lock held: COMPLETED means a concurrent duplicate finished
     *    first — replay it. IN_PROGRESS means nobody completed it: either we
     *    just reserved it, or the previous owner's transaction rolled back
     *    (business failure, failed commit, crashed process) and took all its
     *    business writes with it. Either way it's safe to run the operation.
     * 5. Run the operation and mark the key COMPLETED in the same outer
     *    transaction, so the payment and its cached response commit or roll
     *    back together.
     *
     * Nothing ever deletes the placeholder on failure. A REQUIRES_NEW delete
     * would wait on the very row lock its own outer transaction holds; and
     * cleanup that has to run never runs when the failure is the commit
     * itself, or a dead process — which is how keys used to get stuck.
     */
    private PaymentResponse handleIdempotentRequest(String idempotencyKey,
                                                     String requestUri,
                                                     String requestBodyHash,
                                                     Supplier<PaymentResponse> operation) {
        validateIdempotencyKey(idempotencyKey);
        String actor = CurrentActor.resolve();

        Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByActorAndKeyValue(actor, idempotencyKey);
        if (existing.isPresent() && existing.get().isCompleted()) {
            return replay(existing.get(), requestUri, requestBodyHash);
        }
        if (existing.isEmpty()) {
            try {
                idempotencyService.reservePlaceholder(actor, idempotencyKey, requestUri, requestBodyHash);
            } catch (DataIntegrityViolationException ex) {
                log.debug("Idempotency key reserved by a concurrent request, waiting on its lock: key={}", idempotencyKey);
            }
        }

        IdempotencyKey key = idempotencyKeyRepository.findByActorAndKeyValueForUpdate(actor, idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency placeholder was unexpectedly removed"));
        if (key.isCompleted()) {
            return replay(key, requestUri, requestBodyHash);
        }
        if (!key.matches(requestUri, requestBodyHash)) {
            log.info("Taking over abandoned idempotency key for a different request: key={}", idempotencyKey);
        }
        key.takeOver(requestUri, requestBodyHash);

        PaymentResponse response = operation.get();
        key.complete(serializeResponse(response));
        idempotencyKeyRepository.save(key);
        return response;
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header must not be blank.");
        }
        if (idempotencyKey.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BadRequestException("Idempotency-Key must be at most " + MAX_IDEMPOTENCY_KEY_LENGTH + " characters.");
        }
    }

    private PaymentResponse replay(IdempotencyKey key, String requestUri, String requestBodyHash) {
        if (!key.matches(requestUri, requestBodyHash)) {
            throw new IdempotencyKeyReuseException("Idempotency key reuse with different request body is not allowed.");
        }
        log.debug("Returning cached idempotent response for key={}", key.getKeyValue());
        return deserializeResponse(key.getResponsePayload());
    }

    /**
     * BigDecimals are hashed by numeric value, not representation: a client
     * that sends 100 and retries with 100.00 is retrying the same request.
     */
    private String generateRequestBodyHash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                String canonical = value instanceof BigDecimal decimal
                        ? decimal.stripTrailingZeros().toPlainString()
                        : (value == null ? "" : value.toString());
                digest.update(canonical.getBytes(StandardCharsets.UTF_8));
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
                OffsetDateTime.now(),
                authorization.getOriginalAmount(),
                authorization.getDiscountAmount(),
                authorization.getDiscountReasonCode()
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
                transaction.getCreatedAt(),
                transaction.getOriginalAmount(),
                transaction.getDiscountAmount(),
                transaction.getDiscountReasonCode()
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
