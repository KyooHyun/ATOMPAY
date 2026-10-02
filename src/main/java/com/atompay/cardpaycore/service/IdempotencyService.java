package com.atompay.cardpaycore.service;

import com.atompay.cardpaycore.domain.entity.IdempotencyKey;
import com.atompay.cardpaycore.repository.IdempotencyKeyRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdempotencyService {

    private final IdempotencyKeyRepository idempotencyKeyRepository;

    public IdempotencyService(IdempotencyKeyRepository idempotencyKeyRepository) {
        this.idempotencyKeyRepository = idempotencyKeyRepository;
    }

    /**
     * Inserts the placeholder in its own committed transaction, so the row
     * exists (and the unique constraint arbitrates) before the outer
     * transaction does any business work.
     *
     * Throws DataIntegrityViolationException if another request already
     * reserved this key. It must not be caught in here: by then the
     * repository has marked this transaction rollback-only, and swallowing
     * the exception turns it into an UnexpectedRollbackException at commit.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reservePlaceholder(String actor, String keyValue, String requestUri, String requestBodyHash) {
        idempotencyKeyRepository.saveAndFlush(IdempotencyKey.placeholder(actor, keyValue, requestUri, requestBodyHash));
    }
}
