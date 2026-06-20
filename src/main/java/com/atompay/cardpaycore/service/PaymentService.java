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
import com.atompay.cardpaycore.repository.AuthorizationRepository;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import com.atompay.cardpaycore.repository.IdempotencyKeyRepository;
import com.atompay.cardpaycore.repository.PaymentTransactionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private final CardAccountRepository cardAccountRepository;
    private final AuthorizationRepository authorizationRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final PaymentTransactionRepository paymentTransactionRepository;

    public PaymentService(CardAccountRepository cardAccountRepository,
                          AuthorizationRepository authorizationRepository,
                          IdempotencyKeyRepository idempotencyKeyRepository,
                          PaymentTransactionRepository paymentTransactionRepository) {
        this.cardAccountRepository = cardAccountRepository;
        this.authorizationRepository = authorizationRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
        this.paymentTransactionRepository = paymentTransactionRepository;
    }

    @Transactional
    public PaymentResponse authorize(AuthorizeRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required.");
        }

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
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required.");
        }

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
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required.");
        }

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
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required.");
        }

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
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required.");
        }

        String requestBodyHash = generateRequestBodyHash(authorizationId, amount);
        return handleIdempotentRequest(
                idempotencyKey,
                "/api/v1/payments/" + authorizationId + "/refund",
                requestBodyHash,
                () -> doRefund(authorizationId, amount)
        );
    }

    private PaymentResponse createAuthorization(AuthorizeRequest request) {
        CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(request.getCardId())
                .orElseThrow(() -> new BadRequestException("Card account not found."));

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

        log.info("Authorization created: authorizationId={}, cardId={}, amount={}",
                authorizationId, cardAccount.getCardId(), request.getAmount());

        return mapToResponse(authorization);
    }

    private PaymentResponse doCapture(String authorizationId, BigDecimal amount) {
        Authorization authorization = authorizationRepository.findByAuthorizationIdForUpdate(authorizationId)
                .orElseThrow(() -> new BadRequestException("Authorization not found."));

        authorization.capture(amount);
        authorizationRepository.save(authorization);
        recordTransaction(authorization, TransactionType.CAPTURE, amount);

        log.info("Payment captured: authorizationId={}, amount={}", authorizationId, amount);

        return mapToResponse(authorization);
    }

    private PaymentResponse doCancel(String authorizationId) {
        Authorization authorization = authorizationRepository.findByAuthorizationIdForUpdate(authorizationId)
                .orElseThrow(() -> new BadRequestException("Authorization not found."));

        authorization.cancel();
        authorizationRepository.save(authorization);

        CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(authorization.getCardId())
                .orElseThrow(() -> new BadRequestException("Card account not found."));
        cardAccount.increaseAvailableAmount(authorization.getAmount());
        cardAccountRepository.save(cardAccount);
        recordTransaction(authorization, TransactionType.CANCEL, authorization.getAmount());

        log.info("Authorization cancelled: authorizationId={}, restoredAmount={}", authorizationId, authorization.getAmount());

        return mapToResponse(authorization);
    }

    private PaymentResponse doPartialRefund(String authorizationId, BigDecimal amount) {
        Authorization authorization = authorizationRepository.findByAuthorizationIdForUpdate(authorizationId)
                .orElseThrow(() -> new BadRequestException("Authorization not found."));

        authorization.partialRefund(amount);
        authorizationRepository.save(authorization);

        CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(authorization.getCardId())
                .orElseThrow(() -> new BadRequestException("Card account not found."));
        cardAccount.increaseAvailableAmount(amount);
        cardAccountRepository.save(cardAccount);
        recordTransaction(authorization, TransactionType.PARTIAL_REFUND, amount);

        log.info("Partial refund processed: authorizationId={}, amount={}, totalRefunded={}",
                authorizationId, amount, authorization.getRefundedAmount());

        return mapToResponse(authorization);
    }

    private PaymentResponse doRefund(String authorizationId, BigDecimal amount) {
        Authorization authorization = authorizationRepository.findByAuthorizationIdForUpdate(authorizationId)
                .orElseThrow(() -> new BadRequestException("Authorization not found."));

        boolean fullRefund = authorization.getRemainingRefundableAmount().compareTo(amount) == 0;
        authorization.refund(amount);
        authorizationRepository.save(authorization);

        CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(authorization.getCardId())
                .orElseThrow(() -> new BadRequestException("Card account not found."));
        cardAccount.increaseAvailableAmount(amount);
        cardAccountRepository.save(cardAccount);
        recordTransaction(authorization, fullRefund ? TransactionType.REFUND : TransactionType.PARTIAL_REFUND, amount);

        log.info("Refund processed: authorizationId={}, amount={}, full={}", authorizationId, amount, fullRefund);

        return mapToResponse(authorization);
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

    private PaymentResponse handleIdempotentRequest(String idempotencyKey,
                                                     String requestUri,
                                                     String requestBodyHash,
                                                     Supplier<PaymentResponse> operation) {
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByKeyValue(idempotencyKey);
        if (existingKey.isPresent()) {
            IdempotencyKey key = existingKey.get();
            if (!key.getRequestUri().equals(requestUri) || !key.getRequestBodyHash().equals(requestBodyHash)) {
                throw new BadRequestException("Idempotency key reuse with different request body is not allowed.");
            }
            if (key.getResponsePayload().isBlank()) {
                throw new BadRequestException("Idempotency key is already being processed. Retry later.");
            }
            log.debug("Returning cached idempotent response for key={}", idempotencyKey);
            return deserializeResponse(key.getResponsePayload());
        }

        IdempotencyKey placeholder = new IdempotencyKey(
                idempotencyKey,
                requestUri,
                requestBodyHash,
                "",
                OffsetDateTime.now()
        );
        try {
            placeholder = idempotencyKeyRepository.save(placeholder);
        } catch (DataIntegrityViolationException ex) {
            Optional<IdempotencyKey> conflictingKey = idempotencyKeyRepository.findByKeyValue(idempotencyKey);
            if (conflictingKey.isPresent()) {
                IdempotencyKey key = conflictingKey.get();
                if (!key.getRequestUri().equals(requestUri) || !key.getRequestBodyHash().equals(requestBodyHash)) {
                    throw new BadRequestException("Idempotency key reuse with different request body is not allowed.");
                }
                if (key.getResponsePayload().isBlank()) {
                    throw new BadRequestException("Idempotency key is already being processed. Retry later.");
                }
                return deserializeResponse(key.getResponsePayload());
            }
            throw ex;
        }

        PaymentResponse response = operation.get();
        placeholder.setResponsePayload(serializeResponse(response));
        idempotencyKeyRepository.save(placeholder);
        return response;
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
                authorization.getStatus().name(),
                OffsetDateTime.now()
        ));
    }

    public List<PaymentTransactionResponse> listTransactions(String authorizationId) {
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new BadRequestException("Authorization ID is required.");
        }
        return paymentTransactionRepository.findByAuthorizationId(authorizationId).stream()
                .map(this::mapToTransactionResponse)
                .toList();
    }

    public PaymentResponse getPayment(String authorizationId) {
        if (authorizationId == null || authorizationId.isBlank()) {
            throw new BadRequestException("Authorization ID is required.");
        }
        Authorization authorization = authorizationRepository.findByAuthorizationId(authorizationId)
                .orElseThrow(() -> new BadRequestException("Authorization not found."));
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
            return OBJECT_MAPPER.writeValueAsString(response);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize idempotent response", ex);
        }
    }

    private PaymentResponse deserializeResponse(String payload) {
        try {
            return OBJECT_MAPPER.readValue(payload, PaymentResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to deserialize idempotent response", ex);
        }
    }
}
