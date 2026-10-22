package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import com.dentalplatform.erp.configsecurity.domain.model.AuditLog;
import com.dentalplatform.erp.configsecurity.domain.repository.AuditLogRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
class AuditLogRepositoryAdapter implements AuditLogRepository {

    private final AuditLogJpaRepository repository;

    AuditLogRepositoryAdapter(AuditLogJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public AuditLog guardar(AuditLog l) {
        return toDomain(repository.save(new AuditLogJpaEntity(l.id(), l.usuarioId(), l.accion(), l.entidadAfectada(),
                l.entidadId(), l.detalleJson(), l.ipOrigen(), l.createdAt())));
    }

    @Override
    public List<AuditLog> ultimos(int limite) {
        return repository.findAllByOrderByCreatedAtDesc(PageRequest.of(0, limite)).stream()
                .map(AuditLogRepositoryAdapter::toDomain)
                .toList();
    }

    private static AuditLog toDomain(AuditLogJpaEntity e) {
        return new AuditLog(e.getId(), e.getUsuarioId(), e.getAccion(), e.getEntidadAfectada(), e.getEntidadId(),
                e.getDetalleJson(), e.getIpOrigen(), e.getCreatedAt());
    }
}
