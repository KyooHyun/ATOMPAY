package com.atompay.cardpaycore.repository;

import com.atompay.cardpaycore.domain.entity.IdempotencyKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.util.Optional;

public interface IdempotencyKeyRepository extends JpaRepository<IdempotencyKey, Long> {
    Optional<IdempotencyKey> findByKeyValue(String keyValue);

    /**
     * Locking read that bypasses the transaction's REPEATABLE READ snapshot.
     * A plain findByKeyValue re-read here would still see "not found" if the
     * placeholder was inserted by a sibling REQUIRES_NEW transaction after
     * this transaction's first (snapshot-establishing) read -- InnoDB locking
     * reads always read the latest committed row, not the snapshot.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select k from IdempotencyKey k where k.keyValue = :keyValue")
    Optional<IdempotencyKey> findByKeyValueForUpdate(@Param("keyValue") String keyValue);

    void deleteByKeyValue(String keyValue);
}
