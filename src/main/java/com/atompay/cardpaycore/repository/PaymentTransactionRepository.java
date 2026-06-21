package com.atompay.cardpaycore.repository;

import com.atompay.cardpaycore.domain.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
    List<PaymentTransaction> findByAuthorizationIdOrderByCreatedAtAsc(String authorizationId);
}
