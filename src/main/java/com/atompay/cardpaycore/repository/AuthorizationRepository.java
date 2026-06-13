package com.atompay.cardpaycore.repository;

import com.atompay.cardpaycore.domain.entity.Authorization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AuthorizationRepository extends JpaRepository<Authorization, Long> {
    Optional<Authorization> findByAuthorizationId(String authorizationId);
}
