package com.atompay.cardpaycore;

import com.atompay.cardpaycore.config.JacksonConfig;
import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.domain.enums.CardAccountStatus;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.repository.AuthorizationRepository;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import com.atompay.cardpaycore.service.AuditLogService;
import com.atompay.cardpaycore.service.IdempotencyService;
import com.atompay.cardpaycore.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The connection pool is a lock too. If a request holds one pooled
 * connection (its outer transaction) while asking for a second one (a
 * REQUIRES_NEW transaction), then pool-size concurrent requests can each
 * hold one and wait forever for another — no row is contended, nobody
 * progresses, and every request fails at Hikari's connection timeout.
 *
 * A deliberately tiny pool makes that reachable with a handful of threads.
 * The requests use different cards, so no row lock is shared between them.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PaymentService.class, IdempotencyService.class, AuditLogService.class, JacksonConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConnectionPoolStarvationMySqlTest {

    private static final int POOL_SIZE = 3;
    private static final int CONCURRENT_REQUESTS = 12;

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.1.0")
            .withDatabaseName("cardpay")
            .withUsername("test")
            .withPassword("test");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> mysql.getJdbcUrl() + "?useSSL=false&allowPublicKeyRetrieval=true");
        registry.add("spring.datasource.username", mysql::getUsername);
        registry.add("spring.datasource.password", mysql::getPassword);
        registry.add("spring.datasource.driver-class-name", mysql::getDriverClassName);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.MySQLDialect");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> POOL_SIZE);
        registry.add("spring.datasource.hikari.connection-timeout", () -> 3000);
    }

    @Autowired
    private CardAccountRepository cardAccountRepository;

    @Autowired
    private AuthorizationRepository authorizationRepository;

    @Autowired
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            String cardId = "CARD-POOL-" + i;
            if (cardAccountRepository.findByCardId(cardId).isEmpty()) {
                cardAccountRepository.save(new CardAccount(cardId, "4111-0000-0000-" + i,
                        BigDecimal.valueOf(1_000_000), BigDecimal.valueOf(1_000_000), CardAccountStatus.ACTIVE));
            }
        }
    }

    @Test
    void moreConcurrentRequestsThanPooledConnectionsShouldAllSucceed() throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(CONCURRENT_REQUESTS);
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_REQUESTS);
        CopyOnWriteArrayList<String> failures = new CopyOnWriteArrayList<>();
        long before = authorizationRepository.count();

        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    AuthorizeRequest request = new AuthorizeRequest();
                    request.setCardId("CARD-POOL-" + index);
                    request.setAmount(BigDecimal.valueOf(1_000));
                    paymentService.authorize(request, "pool-" + index + "-" + System.nanoTime());
                } catch (Exception ex) {
                    failures.add(ex.getClass().getSimpleName() + ": " + ex.getMessage());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdownNow();

        assertThat(finished).isTrue();
        assertThat(failures).isEmpty();
        assertThat(authorizationRepository.count() - before).isEqualTo(CONCURRENT_REQUESTS);
    }
}
