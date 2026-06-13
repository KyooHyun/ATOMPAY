package com.atompay.cardpaycore;

import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.dto.AuthorizeRequest;
import com.atompay.cardpaycore.dto.PaymentResponse;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import com.atompay.cardpaycore.service.PaymentService;
import jakarta.transaction.Transactional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(PaymentService.class)
class PaymentServiceTest {

    @Autowired
    private CardAccountRepository cardAccountRepository;

    @Autowired
    private PaymentService paymentService;

    @BeforeEach
    void setUp() {
        cardAccountRepository.deleteAll();
        cardAccountRepository.save(new CardAccount("CARD-001", "4111-1111-1111-1111", BigDecimal.valueOf(5_000_000), BigDecimal.valueOf(5_000_000), "ACTIVE"));
    }

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

        Optional<CardAccount> account = cardAccountRepository.findByCardId("CARD-001");
        assertThat(account).isPresent();
        assertThat(account.get().getAvailableAmount()).isEqualByComparingTo(BigDecimal.valueOf(4_900_000));
    }
}
