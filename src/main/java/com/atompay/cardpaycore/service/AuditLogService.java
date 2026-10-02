package com.atompay.cardpaycore.service;

import com.atompay.cardpaycore.domain.entity.AuditLog;
import com.atompay.cardpaycore.domain.enums.TransactionType;
import com.atompay.cardpaycore.repository.AuditLogRepository;
import com.atompay.cardpaycore.security.CurrentActor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

@Service
public class AuditLogService {

    /** audit_log.failure_reason VARCHAR(255). */
    static final int MAX_FAILURE_REASON_LENGTH = 255;

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
     * PaymentService calls this after the failed business transaction has
     * already rolled back, so it normally starts a fresh transaction.
     * REQUIRES_NEW keeps that true even when some caller up the stack has a
     * transaction open — a failed attempt must stay on record either way.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(TransactionType action, String authorizationId, String cardId,
                              BigDecimal amount, String failureReason) {
        save(action, authorizationId, cardId, amount, false, truncate(failureReason));
    }

    /**
     * Exception messages can be far longer than audit_log.failure_reason —
     * a MySQL deadlock message carries the whole SQL statement. Without this,
     * the audit insert itself failed and replaced the original exception.
     */
    private static String truncate(String failureReason) {
        if (failureReason == null || failureReason.length() <= MAX_FAILURE_REASON_LENGTH) {
            return failureReason;
        }
        return failureReason.substring(0, MAX_FAILURE_REASON_LENGTH - 3) + "...";
    }

    private void save(TransactionType action, String authorizationId, String cardId, BigDecimal amount,
                      boolean success, String failureReason) {
        auditLogRepository.save(new AuditLog(
                CurrentActor.resolve(),
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
}
