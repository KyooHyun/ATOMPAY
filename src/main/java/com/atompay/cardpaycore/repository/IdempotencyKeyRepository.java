package com.atompay.cardpaycore.repository;

import com.atompay.cardpaycore.domain.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    Optional<IdempotencyKey> findByActorAndKeyValue(String actor, String keyValue);

    /**
     * Locking read. Two jobs:
     * 1. It bypasses the transaction's REPEATABLE READ snapshot. A plain
     *    re-read would still see "not found" if the placeholder was inserted
     *    by a sibling REQUIRES_NEW transaction after this transaction's first
     *    (snapshot-establishing) read -- InnoDB locking reads always read the
     *    latest committed row.
     * 2. Holding the lock until commit is what marks the key as owned. A
     *    duplicate request blocks here until the owner commits or rolls back.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from IdempotencyKey k where k.actor = :actor and k.keyValue = :keyValue")
    Optional<IdempotencyKey> findByActorAndKeyValueForUpdate(@Param("actor") String actor,
                                                             @Param("keyValue") String keyValue);
}
