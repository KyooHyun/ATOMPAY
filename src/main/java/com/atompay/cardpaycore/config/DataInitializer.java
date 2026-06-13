package com.atompay.cardpaycore.config;

import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initDatabase(CardAccountRepository cardAccountRepository) {
        return args -> {
            if (cardAccountRepository.findByCardId("CARD-001").isEmpty()) {
                CardAccount cardAccount = new CardAccount(
                        "CARD-001",
                        "4111-1111-1111-1111",
                        BigDecimal.valueOf(5_000_000),
                        BigDecimal.valueOf(5_000_000),
                        "ACTIVE"
                );
                cardAccountRepository.save(cardAccount);
            }
        };
    }
}
