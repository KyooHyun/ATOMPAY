package com.atompay.cardpaycore.service;

import com.atompay.cardpaycore.domain.entity.AuditLog;
import com.atompay.cardpaycore.domain.enums.TransactionType;
import com.atompay.cardpaycore.repository.AuditLogRepository;
import org.slf4j.MDC;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
     * audit entry commits atomically with the business action it describes.
     */
    public void recordSuccess(TransactionType action, String authorizationId, String cardId, BigDecimal amount) {
        save(action, authorizationId, cardId, amount, true, null);
    }

    /**
     * REQUIRES_NEW: the caller's transaction is about to roll back, so this
     * entry must commit independently or it would roll back with it.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(TransactionType action, String authorizationId, String cardId,
                              BigDecimal amount, String failureReason) {
        save(action, authorizationId, cardId, amount, false, failureReason);
    }

    private void save(TransactionType action, String authorizationId, String cardId, BigDecimal amount,
                      boolean success, String failureReason) {
        auditLogRepository.save(new AuditLog(
                resolveActor(),
                action,
                authorizationId,
                cardId,
                amount,
                success,
                failureReason,
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
