package com.atompay.cardpaycore;

import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.domain.enums.CardAccountStatus;
import com.atompay.cardpaycore.domain.enums.TransactionType;
import com.atompay.cardpaycore.dto.AmountRequest;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.dto.PaymentResponse;
import com.atompay.cardpaycore.dto.PaymentTransactionResponse;
import com.atompay.cardpaycore.exception.BadRequestException;
import com.atompay.cardpaycore.repository.AuthorizationRepository;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import com.atompay.cardpaycore.repository.IdempotencyKeyRepository;
import com.atompay.cardpaycore.repository.PaymentTransactionRepository;
import com.atompay.cardpaycore.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DataJpaTest
@Import(PaymentService.class)
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
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        paymentTransactionRepository.deleteAll();
        idempotencyKeyRepository.deleteAll();
        authorizationRepository.deleteAll();
        cardAccountRepository.deleteAll();
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
