package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface TransaccionQrJpaRepository extends JpaRepository<TransaccionQrJpaEntity, UUID> {
    @Query("select t from TransaccionQrJpaEntity t where t.reference = :reference or t.companyReference = :reference")
    Optional<TransaccionQrJpaEntity> findByReference(@Param("reference") String reference);

    Optional<TransaccionQrJpaEntity> findFirstByCuotaIdAndEstadoAndExpiresAfterOrderByCreatedDesc(
            UUID cuotaId, String estado, java.time.OffsetDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransaccionQrJpaEntity t where t.reference = :reference or t.companyReference = :reference")
    Optional<TransaccionQrJpaEntity> bloquear(@Param("reference") String reference);
}
