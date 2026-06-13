package com.atompay.cardpaycore.repository;

import com.atompay.cardpaycore.domain.entity.CardAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface CardAccountRepository extends JpaRepository<CardAccount, Long> {
    Optional<CardAccount> findByCardId(String cardId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from CardAccount c where c.cardId = :cardId")
    Optional<CardAccount> findByCardIdForUpdate(@Param("cardId") String cardId);
}
