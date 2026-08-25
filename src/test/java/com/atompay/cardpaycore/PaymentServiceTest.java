package com.atompay.cardpaycore;

import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.domain.enums.CardAccountStatus;
import com.atompay.cardpaycore.domain.enums.TransactionType;
import com.atompay.cardpaycore.dto.AmountRequest;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.dto.PaymentResponse;
import com.atompay.cardpaycore.dto.PaymentTransactionResponse;
import com.atompay.cardpaycore.domain.entity.AuditLog;
import com.atompay.cardpaycore.dto.AuditLogResponse;
import com.atompay.cardpaycore.exception.BadRequestException;
import com.atompay.cardpaycore.repository.AuditLogRepository;
import com.atompay.cardpaycore.repository.AuthorizationRepository;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import com.atompay.cardpaycore.repository.IdempotencyKeyRepository;
import com.atompay.cardpaycore.repository.PaymentTransactionRepository;
import com.atompay.cardpaycore.config.JacksonConfig;
import com.atompay.cardpaycore.service.AuditLogService;
import com.atompay.cardpaycore.service.IdempotencyService;
import com.atompay.cardpaycore.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.transaction.BeforeTransaction;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@Import({PaymentService.class, IdempotencyService.class, AuditLogService.class, JacksonConfig.class})
class PaymentServiceTest {

    @Autowired
    private CardAccountRepository cardAccountRepository;

    @Autowired
    private AuthorizationRepository authorizationRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    @Autowired
    private PaymentTransactionRepository paymentTransactionRepository;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private PaymentService paymentService;

    @BeforeTransaction
    void setUp() {
        auditLogRepository.deleteAllInBatch();
        paymentTransactionRepository.deleteAllInBatch();
        idempotencyKeyRepository.deleteAllInBatch();
        authorizationRepository.deleteAllInBatch();
        cardAccountRepository.deleteAllInBatch();
        cardAccountRepository.save(new CardAccount("CARD-001", "4111-1111-1111-1111", BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(5_000_000), CardAccountStatus.ACTIVE));
    }

    // ── Authorize ──────────────────────────────────────────────────────────────

    @Test
    void authorizeShouldCreateAuthorizationAndDeductAvailableAmount() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));

        PaymentResponse response = paymentService.authorize(request, "key-123");

        assertThat(response.getAuthorizationId()).isNotNull();
        assertThat(response.getCardId()).isEqualTo("CARD-001");
        assertThat(response.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(100_000));
        assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
        assertThat(response.getRefundedAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        Optional<CardAccount> account = cardAccountRepository.findByCardId("CARD-001");
        assertThat(account).isPresent();
        assertThat(account.get().getAvailableAmount()).isEqualByComparingTo(BigDecimal.valueOf(4_900_000));
    }

    @Test
    void authorizeShouldReturnSameResponseForRepeatedIdempotencyKey() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));

        PaymentResponse firstResponse = paymentService.authorize(request, "key-123");
        PaymentResponse secondResponse = paymentService.authorize(request, "key-123");

        assertThat(secondResponse.getAuthorizationId()).isEqualTo(firstResponse.getAuthorizationId());
        assertThat(authorizationRepository.count()).isEqualTo(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    void authorizeShouldRejectIdempotencyReuseWithDifferentBody() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        paymentService.authorize(request, "key-123");

        AuthorizeRequest differentRequest = new AuthorizeRequest();
        differentRequest.setCardId("CARD-001");
        differentRequest.setAmount(BigDecimal.valueOf(200_000));

        assertThrows(BadRequestException.class,
                () -> paymentService.authorize(differentRequest, "key-123"));
    }

    @Test
    void authorizeShouldRejectInactiveCard() {
        cardAccountRepository.deleteAll();
        cardAccountRepository.save(new CardAccount("CARD-002", "4111-1111-1111-2222", BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(5_000_000), CardAccountStatus.BLOCKED));

        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-002");
        request.setAmount(BigDecimal.valueOf(100_000));

        assertThrows(BadRequestException.class, () -> paymentService.authorize(request, "key-blocked"));
    }

    // ── Zero-amount verification authorization ────────────────────────────────

    @Test
    void authorizeShouldAllowZeroAmountWithoutDeductingAvailableAmount() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.ZERO);

        PaymentResponse response = paymentService.authorize(request, "key-zero-auth");

        assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
        assertThat(response.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(cardAccountRepository.findByCardId("CARD-001").get().getAvailableAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }

    @Test
    void authorizeShouldRejectZeroAmountOnBlockedCard() {
        cardAccountRepository.deleteAll();
        cardAccountRepository.save(new CardAccount("CARD-002", "4111-1111-1111-2222", BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(5_000_000), CardAccountStatus.BLOCKED));

        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-002");
        request.setAmount(BigDecimal.ZERO);

        assertThrows(BadRequestException.class, () -> paymentService.authorize(request, "key-zero-blocked"));
    }

    @Test
    void captureShouldAllowZeroAmountOnZeroAmountAuthorization() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.ZERO);
        PaymentResponse authorization = paymentService.authorize(request, "key-zero-auth");

        PaymentResponse captureResponse = paymentService.capture(authorization.getAuthorizationId(), BigDecimal.ZERO, "key-zero-capture");

        assertThat(captureResponse.getStatus()).isEqualTo("CAPTURED");
    }

    @Test
    void cancelShouldNotTouchAvailableAmountForZeroAmountAuthorization() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.ZERO);
        PaymentResponse authorization = paymentService.authorize(request, "key-zero-auth");

        PaymentResponse cancelResponse = paymentService.cancel(authorization.getAuthorizationId(), "key-zero-cancel");

        assertThat(cancelResponse.getStatus()).isEqualTo("CANCELLED");
        assertThat(cardAccountRepository.findByCardId("CARD-001").get().getAvailableAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }

    // ── Discounted-zero authorization (merchant-submitted waiver, e.g. 국가유공자) ──

    private AuthorizeRequest discountedRequest(BigDecimal originalAmount, BigDecimal discountAmount, BigDecimal amount, String reasonCode) {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(amount);
        request.setOriginalAmount(originalAmount);
        request.setDiscountAmount(discountAmount);
        request.setDiscountReasonCode(reasonCode);
        return request;
    }

    @Test
    void authorizeShouldAcceptFullDiscountToZeroWithoutTouchingAvailableAmount() {
        AuthorizeRequest request = discountedRequest(BigDecimal.valueOf(30_000), BigDecimal.valueOf(30_000), BigDecimal.ZERO, "NATIONAL_MERIT_RECIPIENT");

        PaymentResponse response = paymentService.authorize(request, "key-discount-1");

        assertThat(response.getStatus()).isEqualTo("AUTHORIZED");
        assertThat(response.getKind()).isEqualTo("DISCOUNTED_ZERO");
        assertThat(response.getAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.getOriginalAmount()).isEqualByComparingTo(BigDecimal.valueOf(30_000));
        assertThat(response.getDiscountAmount()).isEqualByComparingTo(BigDecimal.valueOf(30_000));
        assertThat(response.getDiscountReasonCode()).isEqualTo("NATIONAL_MERIT_RECIPIENT");
        assertThat(cardAccountRepository.findByCardId("CARD-001").get().getAvailableAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }

    @Test
    void authorizeShouldRejectDiscountArithmeticMismatch() {
        // 30,000 - 20,000 = 10,000, but charged amount claims 0
        AuthorizeRequest request = discountedRequest(BigDecimal.valueOf(30_000), BigDecimal.valueOf(20_000), BigDecimal.ZERO, "NATIONAL_MERIT_RECIPIENT");

        assertThrows(BadRequestException.class, () -> paymentService.authorize(request, "key-discount-mismatch"));
    }

    @Test
    void authorizeShouldRejectDiscountExceedingOriginalAmount() {
        AuthorizeRequest request = discountedRequest(BigDecimal.valueOf(10_000), BigDecimal.valueOf(20_000), BigDecimal.ZERO, "NATIONAL_MERIT_RECIPIENT");

        assertThrows(BadRequestException.class, () -> paymentService.authorize(request, "key-discount-exceeds"));
    }

    @Test
    void authorizeShouldRejectUnknownDiscountReasonCode() {
        AuthorizeRequest request = discountedRequest(BigDecimal.valueOf(30_000), BigDecimal.valueOf(30_000), BigDecimal.ZERO, "MADE_UP_CODE");

        assertThrows(BadRequestException.class, () -> paymentService.authorize(request, "key-discount-badcode"));
    }

    @Test
    void authorizeShouldRejectPartialDiscountFields() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.ZERO);
        request.setDiscountAmount(BigDecimal.valueOf(30_000)); // originalAmount/reasonCode missing

        assertThrows(BadRequestException.class, () -> paymentService.authorize(request, "key-discount-partial"));
    }

    @Test
    void verificationAndDiscountedZeroAuthorizationsShouldBeDistinguishableInTheLedger() {
        AuthorizeRequest verification = new AuthorizeRequest();
        verification.setCardId("CARD-001");
        verification.setAmount(BigDecimal.ZERO);
        PaymentResponse verificationAuth = paymentService.authorize(verification, "key-verification");

        AuthorizeRequest discounted = discountedRequest(BigDecimal.valueOf(30_000), BigDecimal.valueOf(30_000), BigDecimal.ZERO, "NATIONAL_MERIT_RECIPIENT");
        PaymentResponse discountedAuth = paymentService.authorize(discounted, "key-discounted");

        assertThat(verificationAuth.getKind()).isEqualTo("VERIFICATION");
        assertThat(verificationAuth.getOriginalAmount()).isNull();

        assertThat(discountedAuth.getKind()).isEqualTo("DISCOUNTED_ZERO");
        assertThat(discountedAuth.getOriginalAmount()).isEqualByComparingTo(BigDecimal.valueOf(30_000));

        assertThat(paymentService.listTransactions(verificationAuth.getAuthorizationId()))
                .extracting(PaymentTransactionResponse::getOriginalAmount)
                .containsExactly((BigDecimal) null);
        assertThat(paymentService.listTransactions(discountedAuth.getAuthorizationId()))
                .extracting(PaymentTransactionResponse::getOriginalAmount)
                .containsExactly(BigDecimal.valueOf(30_000));
    }

    @Test
    void discountedZeroAuthorizationCaptureAndRefundShouldReverseWithoutTouchingAvailableAmount() {
        AuthorizeRequest request = discountedRequest(BigDecimal.valueOf(30_000), BigDecimal.valueOf(30_000), BigDecimal.ZERO, "NATIONAL_MERIT_RECIPIENT");
        PaymentResponse authorization = paymentService.authorize(request, "key-discount-lifecycle");

        PaymentResponse captureResponse = paymentService.capture(authorization.getAuthorizationId(), BigDecimal.ZERO, "key-discount-capture");
        assertThat(captureResponse.getStatus()).isEqualTo("CAPTURED");

        PaymentResponse refundResponse = paymentService.refund(authorization.getAuthorizationId(), BigDecimal.ZERO, "key-discount-refund");
        assertThat(refundResponse.getStatus()).isEqualTo("REFUNDED");

        assertThat(cardAccountRepository.findByCardId("CARD-001").get().getAvailableAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }

    @Test
    void verificationAuthorizationShouldRejectZeroAmountRefund() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.ZERO);
        PaymentResponse authorization = paymentService.authorize(request, "key-verification-refund");
        paymentService.capture(authorization.getAuthorizationId(), BigDecimal.ZERO, "key-verification-refund-capture");

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.refund(authorization.getAuthorizationId(), BigDecimal.ZERO, "key-verification-refund-attempt"));
    }

    // ── Capture ────────────────────────────────────────────────────────────────

    @Test
    void captureShouldTransitionToCapturedAndCreateTransaction() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));

        PaymentResponse authorization = paymentService.authorize(request, "key-123");
        AmountRequest captureRequest = new AmountRequest();
        captureRequest.setAmount(BigDecimal.valueOf(100_000));

        PaymentResponse captureResponse = paymentService.capture(authorization.getAuthorizationId(), captureRequest.getAmount(), "key-capture-1");

        assertThat(captureResponse.getStatus()).isEqualTo("CAPTURED");
        assertThat(paymentTransactionRepository.count()).isEqualTo(2);
    }

    @Test
    void captureShouldReleaseUncapturedRemainderToAvailableAmount() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-123");

        PaymentResponse captureResponse = paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(60_000), "key-capture-partial");

        assertThat(captureResponse.getStatus()).isEqualTo("CAPTURED");
        assertThat(captureResponse.getAmount()).isEqualByComparingTo(BigDecimal.valueOf(60_000));
        // 5,000,000 - 100,000 authorized + 40,000 released remainder
        assertThat(cardAccountRepository.findByCardId("CARD-001").get().getAvailableAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(4_940_000));
    }

    @Test
    void partialCaptureShouldPreserveOriginalAuthorizedAmountInLedgerAndAuditLog() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-123");
        paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(60_000), "key-capture-partial");

        // 현재 상태(Authorization.amount)는 실제 매입액으로 갱신되지만,
        // 원 승인액과 실제 매입액의 괴리는 원장/감사로그에 append-only로 남아야 한다 —
        // 이상거래 탐지 관점에서 이 괴리 자체가 신호이기 때문.
        assertThat(paymentService.getPayment(authorization.getAuthorizationId()).getAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(60_000));

        assertThat(paymentService.listTransactions(authorization.getAuthorizationId()))
                .extracting(PaymentTransactionResponse::getTransactionType, PaymentTransactionResponse::getAmount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(TransactionType.AUTHORIZATION, BigDecimal.valueOf(100_000)),
                        org.assertj.core.groups.Tuple.tuple(TransactionType.CAPTURE, BigDecimal.valueOf(60_000)));

        assertThat(paymentService.listAuditLog(authorization.getAuthorizationId()))
                .extracting(AuditLogResponse::getAction, AuditLogResponse::getAmount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(TransactionType.AUTHORIZATION, BigDecimal.valueOf(100_000)),
                        org.assertj.core.groups.Tuple.tuple(TransactionType.CAPTURE, BigDecimal.valueOf(60_000)));
    }

    @Test
    void partialCaptureShouldBoundSubsequentRefundsByCapturedAmountNotOriginalAuthorization() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-123");
        paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(60_000), "key-capture-partial");

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.refund(authorization.getAuthorizationId(), BigDecimal.valueOf(70_000), "key-over-refund"));

        PaymentResponse refundResponse = paymentService.refund(authorization.getAuthorizationId(), BigDecimal.valueOf(60_000), "key-full-refund");
        assertThat(refundResponse.getStatus()).isEqualTo("REFUNDED");
    }

    @Test
    void captureShouldRejectAmountExceedingAuthorizedAmount() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-123");

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(150_000), "key-over-capture"));
    }

    @Test
    void captureShouldRejectZeroAmountOnNonZeroAuthorization() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-123");

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.capture(authorization.getAuthorizationId(), BigDecimal.ZERO, "key-zero-capture"));
    }

    @Test
    void captureShouldRejectSecondCaptureAfterPartialCapture() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-123");
        paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(60_000), "key-capture-1");

        assertThrows(IllegalStateException.class,
                () -> paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(40_000), "key-capture-2"));
    }

    // ── Cancel ─────────────────────────────────────────────────────────────────

    @Test
    void cancelShouldTransitionToCancelledAndRestoreAvailableAmount() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(200_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-auth");

        assertThat(cardAccountRepository.findByCardId("CARD-001").get().getAvailableAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(4_800_000));

        PaymentResponse cancelResponse = paymentService.cancel(authorization.getAuthorizationId(), "key-cancel");

        assertThat(cancelResponse.getStatus()).isEqualTo("CANCELLED");
        assertThat(cardAccountRepository.findByCardId("CARD-001").get().getAvailableAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
        assertThat(paymentTransactionRepository.count()).isEqualTo(2);
    }

    @Test
    void cancelShouldThrowWhenPaymentAlreadyCaptured() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-auth");
        paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(100_000), "key-capture");

        assertThrows(IllegalStateException.class,
                () -> paymentService.cancel(authorization.getAuthorizationId(), "key-cancel"));
    }

    // ── Refund ─────────────────────────────────────────────────────────────────

    @Test
    void refundShouldRestoreAvailableAmountAndRecordRefundTransaction() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-123");
        paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(100_000), "key-capture-1");

        PaymentResponse refundResponse = paymentService.refund(authorization.getAuthorizationId(), BigDecimal.valueOf(100_000), "key-refund-1");

        assertThat(refundResponse.getStatus()).isEqualTo("REFUNDED");
        assertThat(refundResponse.getRefundedAmount()).isEqualByComparingTo(BigDecimal.valueOf(100_000));
        assertThat(cardAccountRepository.findByCardId("CARD-001").get().getAvailableAmount())
                .isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
        assertThat(paymentTransactionRepository.count()).isEqualTo(3);
    }

    @Test
    void refundShouldThrowWhenAmountExceedsRefundable() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-auth");
        paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(100_000), "key-capture");

        assertThrows(IllegalArgumentException.class,
                () -> paymentService.refund(authorization.getAuthorizationId(), BigDecimal.valueOf(200_000), "key-over-refund"));
    }

    // ── Partial Refund ─────────────────────────────────────────────────────────

    @Test
    void partialRefundShouldReduceRemainingRefundableAmountAndRecordEvent() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-123");
        paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(100_000), "key-capture-1");

        PaymentResponse partialRefundResponse = paymentService.partialRefund(authorization.getAuthorizationId(), BigDecimal.valueOf(40_000), "key-partial-refund-1");

        assertThat(partialRefundResponse.getStatus()).isEqualTo("PARTIALLY_REFUNDED");
        assertThat(partialRefundResponse.getRefundedAmount()).isEqualByComparingTo(BigDecimal.valueOf(40_000));
        assertThat(paymentService.listTransactions(authorization.getAuthorizationId()))
                .extracting(PaymentTransactionResponse::getTransactionType)
                .containsExactly(TransactionType.AUTHORIZATION, TransactionType.CAPTURE, TransactionType.PARTIAL_REFUND);
    }

    @Test
    void partialRefundShouldThrowWhenNotCaptured() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-auth");

        assertThrows(IllegalStateException.class,
                () -> paymentService.partialRefund(authorization.getAuthorizationId(), BigDecimal.valueOf(40_000), "key-partial"));
    }

    // ── Ledger / Query ─────────────────────────────────────────────────────────

    @Test
    void listTransactionsShouldReturnHistoryForAuthorization() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-123");
        paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(100_000), "key-capture-1");

        List<PaymentTransactionResponse> transactions = paymentService.listTransactions(authorization.getAuthorizationId());

        assertThat(transactions).hasSize(2);
        assertThat(transactions).extracting(PaymentTransactionResponse::getTransactionType)
                .containsExactly(TransactionType.AUTHORIZATION, TransactionType.CAPTURE);
    }

    // ── Audit log ──────────────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "admin")
    void authorizeShouldRecordAuditLogEntryWithActor() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));

        PaymentResponse authorization = paymentService.authorize(request, "key-123");

        List<AuditLogResponse> auditLog = paymentService.listAuditLog(authorization.getAuthorizationId());
        assertThat(auditLog).hasSize(1);
        assertThat(auditLog.get(0).getActorUsername()).isEqualTo("admin");
        assertThat(auditLog.get(0).getAction()).isEqualTo(TransactionType.AUTHORIZATION);
        assertThat(auditLog.get(0).getAmount()).isEqualByComparingTo(BigDecimal.valueOf(100_000));
        assertThat(auditLog.get(0).isSuccess()).isTrue();
    }

    @Test
    void authorizeWithoutAuthenticationShouldRecordSystemActor() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));

        PaymentResponse authorization = paymentService.authorize(request, "key-123");

        List<AuditLogResponse> auditLog = paymentService.listAuditLog(authorization.getAuthorizationId());
        assertThat(auditLog).hasSize(1);
        assertThat(auditLog.get(0).getActorUsername()).isEqualTo("system");
    }

    @Test
    @WithMockUser(username = "admin")
    void authorizeShouldRecordAuditLogFailureWhenCreditLimitInsufficient() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(10_000_000));

        assertThrows(BadRequestException.class, () -> paymentService.authorize(request, "key-fail"));

        List<AuditLog> auditLog = auditLogRepository.findAll();
        assertThat(auditLog).hasSize(1);
        assertThat(auditLog.get(0).isSuccess()).isFalse();
        assertThat(auditLog.get(0).getAuthorizationId()).isNull();
        assertThat(auditLog.get(0).getCardId()).isEqualTo("CARD-001");
        assertThat(auditLog.get(0).getFailureReason()).contains("insufficient");
    }

    @Test
    @WithMockUser(username = "admin")
    void cancelShouldRecordAuditLogFailureWhenAlreadyCaptured() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-auth");
        paymentService.capture(authorization.getAuthorizationId(), BigDecimal.valueOf(100_000), "key-capture");

        assertThrows(IllegalStateException.class,
                () -> paymentService.cancel(authorization.getAuthorizationId(), "key-cancel"));

        List<AuditLogResponse> auditLog = paymentService.listAuditLog(authorization.getAuthorizationId());
        assertThat(auditLog).extracting(AuditLogResponse::getAction, AuditLogResponse::isSuccess)
                .contains(org.assertj.core.groups.Tuple.tuple(TransactionType.CANCEL, false));
    }

    @Test
    void getPaymentShouldReturnAuthorizationDetails() {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));
        PaymentResponse authorization = paymentService.authorize(request, "key-123");

        PaymentResponse payment = paymentService.getPayment(authorization.getAuthorizationId());

        assertThat(payment.getAuthorizationId()).isEqualTo(authorization.getAuthorizationId());
        assertThat(payment.getStatus()).isEqualTo("AUTHORIZED");
    }
}
