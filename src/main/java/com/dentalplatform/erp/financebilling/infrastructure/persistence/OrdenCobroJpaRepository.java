package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.EstadoOrdenCobro;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

interface OrdenCobroJpaRepository extends JpaRepository<OrdenCobroJpaEntity, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from OrdenCobroJpaEntity o where o.id = :id")
    Optional<OrdenCobroJpaEntity> bloquear(UUID id);

    boolean existsByConsultaExternaId(UUID consultaId);

    @Query("select o from OrdenCobroJpaEntity o where (:estado is null or o.estado = :estado) and (:pacienteId is null or o.pacienteId = :pacienteId) order by o.createdAt desc, o.id")
    List<OrdenCobroJpaEntity> listar(EstadoOrdenCobro estado, UUID pacienteId);
}
