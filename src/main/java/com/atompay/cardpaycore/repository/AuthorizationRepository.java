package com.atompay.cardpaycore.repository;

import com.atompay.cardpaycore.domain.entity.Authorization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface AuthorizationRepository extends JpaRepository<Authorization, Long> {
    Optional<Authorization> findByAuthorizationId(String authorizationId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Authorization a where a.authorizationId = :authorizationId")
    Optional<Authorization> findByAuthorizationIdForUpdate(@Param("authorizationId") String authorizationId);
}
