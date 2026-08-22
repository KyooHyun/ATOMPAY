package com.atompay.cardpaycore.config;

import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.domain.enums.CardAccountStatus;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;

@Configuration
@Profile("loadtest")
public class LoadTestDataInitializer {

    private static final int CARD_COUNT = 100;
    private static final BigDecimal HUGE_LIMIT = new BigDecimal("999999999999");

    @Bean
    CommandLineRunner initLoadTestCards(CardAccountRepository cardAccountRepository) {
        return args -> {
            for (int i = 1; i <= CARD_COUNT; i++) {
                String cardId = "CARD-LOAD-%03d".formatted(i);
                if (cardAccountRepository.findByCardId(cardId).isEmpty()) {
                    cardAccountRepository.save(new CardAccount(
                            cardId,
                            "4222-2222-2222-%04d".formatted(i),
                            HUGE_LIMIT,
                            HUGE_LIMIT,
                            CardAccountStatus.ACTIVE
                    ));
                }
            }
        };
    }
}
