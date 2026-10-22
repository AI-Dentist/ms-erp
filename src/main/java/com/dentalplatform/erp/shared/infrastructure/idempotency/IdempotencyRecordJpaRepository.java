package com.dentalplatform.erp.shared.infrastructure.idempotency;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;

public interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordJpaEntity, String> {

    @Modifying
    @Query(value = """
            INSERT INTO idempotency_keys (idempotency_key, operation_name, expires_at)
            VALUES (:key, :operation, :expiresAt)
            ON CONFLICT (idempotency_key) DO NOTHING""", nativeQuery = true)
    int tryInsert(@Param("key") String key, @Param("operation") String operation,
                  @Param("expiresAt") OffsetDateTime expiresAt);

    @Modifying
    @Query(value = "UPDATE idempotency_keys SET response_payload = CAST(:payload AS jsonb) WHERE idempotency_key = :key",
            nativeQuery = true)
    int storeResponse(@Param("key") String key, @Param("payload") String payload);

    @Modifying
    @Query("DELETE FROM IdempotencyRecordJpaEntity r WHERE r.key = :key AND r.expiresAt < :now")
    int deleteIfExpired(@Param("key") String key, @Param("now") OffsetDateTime now);

    @Modifying
    @Query("DELETE FROM IdempotencyRecordJpaEntity r WHERE r.expiresAt < :now")
    int purgeExpired(@Param("now") OffsetDateTime now);
}
