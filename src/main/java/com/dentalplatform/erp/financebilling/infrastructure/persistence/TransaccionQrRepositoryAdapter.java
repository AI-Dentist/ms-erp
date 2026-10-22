package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.TransaccionQr;
import com.dentalplatform.erp.financebilling.domain.repository.TransaccionQrRepository;
import org.springframework.stereotype.Component;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;

@Component
class TransaccionQrRepositoryAdapter implements TransaccionQrRepository {
    private final TransaccionQrJpaRepository repository;

    private final EntityManager entityManager;

    TransaccionQrRepositoryAdapter(TransaccionQrJpaRepository repository, EntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    public TransaccionQr guardar(TransaccionQr t) {
        return repository.saveAndFlush(TransaccionQrJpaEntity.fromDomain(t)).toDomain();
    }

    public Optional<TransaccionQr> buscar(String reference) {
        return repository.findByReference(reference).map(TransaccionQrJpaEntity::toDomain);
    }

    public Optional<TransaccionQr> bloquear(String reference) {
        return repository.bloquear(reference).map(entity -> {
            // The initial reference lookup may be in the first-level cache while another callback commits.
            entityManager.refresh(entity, LockModeType.PESSIMISTIC_WRITE);
            return entity.toDomain();
        });
    }
}
