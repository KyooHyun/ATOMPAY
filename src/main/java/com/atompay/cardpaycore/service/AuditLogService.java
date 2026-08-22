package com.atompay.cardpaycore.service;

import com.atompay.cardpaycore.domain.entity.AuditLog;
import com.atompay.cardpaycore.domain.enums.TransactionType;
import com.atompay.cardpaycore.repository.AuditLogRepository;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    /**
     * Joins the caller's ambient transaction (no @Transactional here) so the
     * audit entry commits or rolls back atomically with the business action.
     */
    public void record(TransactionType action, String authorizationId, String cardId, BigDecimal amount) {
        auditLogRepository.save(new AuditLog(
                resolveActor(),
                action,
                authorizationId,
                cardId,
                amount,
                MDC.get("requestId"),
                OffsetDateTime.now()
        ));
    }

    private String resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return "system";
        }
        return auth.getName();
    }
}
