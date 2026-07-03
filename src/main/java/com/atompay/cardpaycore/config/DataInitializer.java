package com.atompay.cardpaycore.config;

import com.atompay.cardpaycore.domain.entity.CardAccount;
import com.atompay.cardpaycore.domain.entity.User;
import com.atompay.cardpaycore.domain.enums.CardAccountStatus;
import com.atompay.cardpaycore.domain.enums.Role;
import com.atompay.cardpaycore.repository.CardAccountRepository;
import com.atompay.cardpaycore.repository.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.math.BigDecimal;

@Configuration
public class DataInitializer {

    @Bean
    CommandLineRunner initDatabase(CardAccountRepository cardAccountRepository,
                                   UserRepository userRepository,
                                   PasswordEncoder passwordEncoder) {
        return args -> {
            if (cardAccountRepository.findByCardId("CARD-001").isEmpty()) {
                cardAccountRepository.save(new CardAccount(
                        "CARD-001",
                        "4111-1111-1111-1111",
                        BigDecimal.valueOf(5_000_000),
                        BigDecimal.valueOf(5_000_000),
                        CardAccountStatus.ACTIVE
                ));
            }

            if (userRepository.findByUsername("admin").isEmpty()) {
                userRepository.save(new User("admin", passwordEncoder.encode("password123"), Role.ADMIN));
            }
        };
    }
}
