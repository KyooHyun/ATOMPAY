package com.atompay.cardpaycore.repository;

import com.atompay.cardpaycore.domain.entity.PaymentTransaction;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {
}
