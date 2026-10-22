package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.UUID;

public interface TransaccionQrJpaRepository extends JpaRepository<TransaccionQrJpaEntity, UUID> {
    Optional<TransaccionQrJpaEntity> findByReference(String reference);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TransaccionQrJpaEntity t where t.reference = :reference")
    Optional<TransaccionQrJpaEntity> bloquear(@Param("reference") String reference);
}
