package com.atompay.cardpaycore;

import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.domain.enums.CardAccountStatus;
import com.atompay.cardpaycore.domain.enums.TransactionType;
import com.atompay.cardpaycore.dto.AmountRequest;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.dto.PaymentResponse;
import com.atompay.cardpaycore.repository.AuthorizationRepository;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import com.atompay.cardpaycore.repository.IdempotencyKeyRepository;
import com.atompay.cardpaycore.repository.PaymentTransactionRepository;
import com.atompay.cardpaycore.config.JacksonConfig;
import com.atompay.cardpaycore.service.IdempotencyService;
import com.atompay.cardpaycore.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentService.class, IdempotencyService.class, JacksonConfig.class})
class PaymentServiceMySqlConcurrencyTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.1.0")
            .withDatabaseName("cardpay")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", mysql::getJdbcUrl);
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
    }

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

    @BeforeTransaction
    void setUp() {
        paymentTransactionRepository.deleteAllInBatch();
        idempotencyKeyRepository.deleteAllInBatch();
        authorizationRepository.deleteAllInBatch();
        cardAccountRepository.deleteAllInBatch();
        cardAccountRepository.save(new CardAccount("CARD-001", "4111-1111-1111-1111", BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(5_000_000), CardAccountStatus.ACTIVE));
    }

    @Test
    void mySqlShouldPreventConcurrentPartialRefundOverRefund() throws InterruptedException {
        AuthorizeRequest request = new AuthorizeRequest();
        request.setCardId("CARD-001");
        request.setAmount(BigDecimal.valueOf(100_000));

        PaymentResponse authorization = paymentService.authorize(request, "key-123");
        AmountRequest captureRequest = new AmountRequest();
        captureRequest.setAmount(BigDecimal.valueOf(100_000));
        paymentService.capture(authorization.getAuthorizationId(), captureRequest.getAmount(), "key-capture-1");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CopyOnWriteArrayList<String> outcomes = new CopyOnWriteArrayList<>();

        for (int index = 0; index < 2; index++) {
            int threadIndex = index;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    paymentService.partialRefund(authorization.getAuthorizationId(), BigDecimal.valueOf(60_000), "key-partial-refund-" + threadIndex);
                    outcomes.add("success-" + threadIndex);
                } catch (Exception ex) {
                    outcomes.add("fail-" + threadIndex + ":" + ex.getClass().getSimpleName());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).anyMatch(result -> result.startsWith("success-"));
        assertThat(outcomes).anyMatch(result -> result.startsWith("fail-"));

        assertThat(paymentTransactionRepository.findByAuthorizationIdOrderByCreatedAtAsc(authorization.getAuthorizationId()))
                .extracting(transaction -> transaction.getTransactionType())
                .contains(TransactionType.AUTHORIZATION, TransactionType.CAPTURE, TransactionType.PARTIAL_REFUND);

        Optional<BigDecimal> refundedAmount = authorizationRepository.findByAuthorizationId(authorization.getAuthorizationId())
                .map(auth -> auth.getRefundedAmount());
        assertThat(refundedAmount).isPresent();
        assertThat(refundedAmount.get()).isEqualByComparingTo(BigDecimal.valueOf(60_000));
    }

    @Test
    void mySqlShouldPreventConcurrentAuthorizationFromExceedingCreditLimit() throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CopyOnWriteArrayList<String> outcomes = new CopyOnWriteArrayList<>();

        for (int index = 0; index < 2; index++) {
            int threadIndex = index;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    AuthorizeRequest request = new AuthorizeRequest();
                    request.setCardId("CARD-001");
                    request.setAmount(BigDecimal.valueOf(3_000_000));
                    paymentService.authorize(request, "key-authorize-" + threadIndex);
                    outcomes.add("success-" + threadIndex);
                } catch (Exception ex) {
                    outcomes.add("fail-" + threadIndex + ":" + ex.getClass().getSimpleName());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(outcomes).hasSize(2);
        assertThat(outcomes).anyMatch(result -> result.startsWith("success-"));
        assertThat(outcomes).anyMatch(result -> result.startsWith("fail-"));

        BigDecimal finalAvailableAmount = cardAccountRepository.findByCardId("CARD-001")
                .map(CardAccount::getAvailableAmount)
                .orElseThrow();
        assertThat(finalAvailableAmount).isEqualByComparingTo(BigDecimal.valueOf(2_000_000));
    }

    @Test
    void mySqlShouldPreserveAllRestoresOnConcurrentCancels() throws InterruptedException {
        // 같은 카드의 서로 다른 두 승인을 동시에 취소할 때
        // CardAccount.availableAmount 복원이 둘 다 반영되어야 한다.
        // 락 없이 findByCardId를 쓰면 두 스레드가 같은 잔액을 읽고 각자 덮어써
        // 한 쪽 복원이 유실된다.
        AuthorizeRequest req1 = new AuthorizeRequest();
        req1.setCardId("CARD-001");
        req1.setAmount(BigDecimal.valueOf(100_000));

        AuthorizeRequest req2 = new AuthorizeRequest();
        req2.setCardId("CARD-001");
        req2.setAmount(BigDecimal.valueOf(200_000));

        PaymentResponse auth1 = paymentService.authorize(req1, "key-auth-1");
        PaymentResponse auth2 = paymentService.authorize(req2, "key-auth-2");

        // 두 승인 후 잔액 확인
        BigDecimal afterAuthorize = cardAccountRepository.findByCardId("CARD-001")
                .map(CardAccount::getAvailableAmount).orElseThrow();
        assertThat(afterAuthorize).isEqualByComparingTo(BigDecimal.valueOf(4_700_000));

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        executor.submit(() -> {
            try {
                startLatch.await();
                paymentService.cancel(auth1.getAuthorizationId(), "key-cancel-1");
            } catch (Exception ignored) {
            } finally {
                endLatch.countDown();
            }
        });
        executor.submit(() -> {
            try {
                startLatch.await();
                paymentService.cancel(auth2.getAuthorizationId(), "key-cancel-2");
            } catch (Exception ignored) {
            } finally {
                endLatch.countDown();
            }
        });

        startLatch.countDown();
        endLatch.await(15, TimeUnit.SECONDS);
        executor.shutdownNow();

        // 두 취소가 모두 성공하면 잔액이 원상복구되어야 한다.
        // 락이 없었다면 한 쪽 복원이 유실되어 4,800,000 또는 4,900,000이 된다.
        BigDecimal finalAmount = cardAccountRepository.findByCardId("CARD-001")
                .map(CardAccount::getAvailableAmount).orElseThrow();
        assertThat(finalAmount).isEqualByComparingTo(BigDecimal.valueOf(5_000_000));
    }
}
