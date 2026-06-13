package com.atompay.cardpaycore.service;

import com.atompay.cardpaycore.domain.entity.Authorization;
import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.domain.entity.IdempotencyKey;
import com.atompay.cardpaycore.domain.enums.AuthorizationStatus;
import com.atompay.cardpaycore.domain.enums.TransactionType;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.dto.PaymentResponse;
import com.atompay.cardpaycore.exception.BadRequestException;
import com.atompay.cardpaycore.repository.AuthorizationRepository;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import com.atompay.cardpaycore.repository.IdempotencyKeyRepository;
import jakarta.transaction.Transactional;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class PaymentService {

    private final CardAccountRepository cardAccountRepository;
    private final AuthorizationRepository authorizationRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public PaymentService(CardAccountRepository cardAccountRepository, AuthorizationRepository authorizationRepository, IdempotencyKeyRepository idempotencyKeyRepository) {
        this.cardAccountRepository = cardAccountRepository;
        this.authorizationRepository = authorizationRepository;
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    @Transactional
    public PaymentResponse authorize(AuthorizeRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BadRequestException("Idempotency-Key header is required.");
        }

        String requestKeyHash = generateRequestBodyHash(request.getCardId(), request.getAmount());
        Optional<IdempotencyKey> existingKey = idempotencyKeyRepository.findByKeyValue(idempotencyKey);
        if (existingKey.isPresent()) {
            return deserializeResponse(existingKey.get().getResponsePayload());
        }

        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new BadRequestException("Amount must be positive.");
        }

        if (request.getAmount().compareTo(BigDecimal.valueOf(1_000_000)) >= 0) {
            throw new BadRequestException("Amount exceeds allowed transaction threshold.");
        }

        CardAccount cardAccount = cardAccountRepository.findByCardIdForUpdate(request.getCardId())
                .orElseThrow(() -> new BadRequestException("Card account not found."));

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
                OffsetDateTime.now(),
                OffsetDateTime.now()
        );
        authorizationRepository.save(authorization);

        PaymentResponse response = new PaymentResponse(
                authorization.getAuthorizationId(),
                authorization.getCardId(),
                authorization.getAmount(),
                authorization.getStatus().name(),
                authorization.getCreatedAt(),
                authorization.getUpdatedAt()
        );

        idempotencyKeyRepository.save(new IdempotencyKey(
                idempotencyKey,
                "/api/v1/payments/authorize",
                requestKeyHash,
                serializeResponse(response),
                OffsetDateTime.now()
        ));

        return response;
    }

    public PaymentResponse capture(String authorizationId, BigDecimal amount) {
        Authorization authorization = authorizationRepository.findByAuthorizationId(authorizationId)
                .orElseThrow(() -> new BadRequestException("Authorization not found."));

        if (authorization.getStatus() != AuthorizationStatus.AUTHORIZED) {
            throw new BadRequestException("Only authorized payments can be captured.");
        }

        if (amount.compareTo(authorization.getAmount()) != 0) {
            throw new BadRequestException("Capture amount must equal the authorized amount.");
        }

        authorization.setStatus(AuthorizationStatus.CAPTURED);
        authorizationRepository.save(authorization);

        return mapToResponse(authorization);
    }

    public PaymentResponse cancel(String authorizationId) {
        Authorization authorization = authorizationRepository.findByAuthorizationId(authorizationId)
                .orElseThrow(() -> new BadRequestException("Authorization not found."));

        if (authorization.getStatus() != AuthorizationStatus.AUTHORIZED && authorization.getStatus() != AuthorizationStatus.CAPTURED) {
            throw new BadRequestException("Only authorized or captured payments can be cancelled.");
        }

        authorization.setStatus(AuthorizationStatus.CANCELLED);
        authorizationRepository.save(authorization);

        CardAccount cardAccount = cardAccountRepository.findByCardId(authorization.getCardId())
                .orElseThrow(() -> new BadRequestException("Card account not found."));
        cardAccount.increaseAvailableAmount(authorization.getAmount());
        cardAccountRepository.save(cardAccount);

        return mapToResponse(authorization);
    }

    public PaymentResponse partialCancel(String authorizationId, BigDecimal amount) {
        Authorization authorization = authorizationRepository.findByAuthorizationId(authorizationId)
                .orElseThrow(() -> new BadRequestException("Authorization not found."));

        if (authorization.getStatus() != AuthorizationStatus.CAPTURED) {
            throw new BadRequestException("Only captured payments can be partially cancelled.");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(authorization.getAmount()) >= 0) {
            throw new BadRequestException("Partial cancel amount must be positive and less than the captured amount.");
        }

        authorization.setStatus(AuthorizationStatus.PARTIALLY_CANCELLED);
        authorizationRepository.save(authorization);

        CardAccount cardAccount = cardAccountRepository.findByCardId(authorization.getCardId())
                .orElseThrow(() -> new BadRequestException("Card account not found."));
        cardAccount.increaseAvailableAmount(amount);
        cardAccountRepository.save(cardAccount);

        return mapToResponse(authorization);
    }

    public PaymentResponse refund(String authorizationId, BigDecimal amount) {
        Authorization authorization = authorizationRepository.findByAuthorizationId(authorizationId)
                .orElseThrow(() -> new BadRequestException("Authorization not found."));

        if (authorization.getStatus() != AuthorizationStatus.CAPTURED && authorization.getStatus() != AuthorizationStatus.PARTIALLY_CANCELLED) {
            throw new BadRequestException("Only captured or partially cancelled payments can be refunded.");
        }

        if (amount.compareTo(BigDecimal.ZERO) <= 0 || amount.compareTo(authorization.getAmount()) > 0) {
            throw new BadRequestException("Refund amount must be positive and not exceed the original captured amount.");
        }

        authorization.setStatus(AuthorizationStatus.REFUNDED);
        authorizationRepository.save(authorization);

        return mapToResponse(authorization);
    }

    private PaymentResponse mapToResponse(Authorization authorization) {
        return new PaymentResponse(
                authorization.getAuthorizationId(),
                authorization.getCardId(),
                authorization.getAmount(),
                authorization.getStatus().name(),
                authorization.getCreatedAt(),
                authorization.getUpdatedAt()
        );
    }

    private String generateRequestBodyHash(String cardId, BigDecimal amount) {
        String text = cardId + ":" + amount.toPlainString();
        return java.util.Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private String serializeResponse(PaymentResponse response) {
        return response.getAuthorizationId() + "," + response.getCardId() + "," + response.getAmount() + "," + response.getStatus();
    }

    private PaymentResponse deserializeResponse(String payload) {
        String[] parts = payload.split(",");
        return new PaymentResponse(parts[0], parts[1], new BigDecimal(parts[2]), parts[3], null, null);
    }
}
