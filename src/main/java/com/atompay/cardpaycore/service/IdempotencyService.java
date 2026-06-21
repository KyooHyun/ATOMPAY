package com.atompay.cardpaycore.service;

import com.atompay.cardpaycore.domain.entity.IdempotencyKey;
import com.atompay.cardpaycore.repository.IdempotencyKeyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
public class IdempotencyService {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public IdempotencyService(IdempotencyKeyRepository idempotencyKeyRepository) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    /**
     * Inserts a placeholder row in its own committed transaction so the outer
     * transaction's EntityManager is never tainted by a constraint violation.
     * Returns true if the placeholder was created (caller may proceed), false
     * if a concurrent request already owns this key (caller must re-read).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryReservePlaceholder(String keyValue, String requestUri, String requestBodyHash) {
        try {
            idempotencyKeyRepository.saveAndFlush(
                    new IdempotencyKey(keyValue, requestUri, requestBodyHash, "", OffsetDateTime.now())
            );
            return true;
        } catch (DataIntegrityViolationException ex) {
            log.debug("Idempotency key already reserved by concurrent request: key={}", keyValue);
            return false;
        }
    }

    /**
     * Deletes the placeholder in its own committed transaction so that a
     * failed business operation does not permanently block retries.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void releaseOnFailure(String keyValue) {
        idempotencyKeyRepository.deleteByKeyValue(keyValue);
        log.debug("Released idempotency placeholder after failure: key={}", keyValue);
    }
}
