package com.atompay.cardpaycore.repository;

import com.atompay.cardpaycore.domain.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findByAuthorizationIdOrderByCreatedAtAsc(String authorizationId);
}
