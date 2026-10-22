package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import com.dentalplatform.erp.configsecurity.domain.model.EmpresaConfig;
import com.dentalplatform.erp.configsecurity.domain.repository.EmpresaConfigRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
class EmpresaConfigRepositoryAdapter implements EmpresaConfigRepository {

    private final EmpresaConfigJpaRepository repository;

    EmpresaConfigRepositoryAdapter(EmpresaConfigJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<EmpresaConfig> obtener() {
        return repository.findFirstByOrderByUpdatedAtDesc().map(EmpresaConfigRepositoryAdapter::toDomain);
    }

    @Override
    public EmpresaConfig guardar(EmpresaConfig c) {
        return toDomain(repository.save(new EmpresaConfigJpaEntity(c.getId(), c.getNombreComercial(),
                c.getRazonSocial(), c.getNit(), c.getDireccion(), c.getTelefonoContacto(), c.getEmailContacto(),
                c.getLogoUrl(), c.getMonedaSimbolo(), c.getUpdatedAt())));
    }

    private static EmpresaConfig toDomain(EmpresaConfigJpaEntity e) {
        return new EmpresaConfig(e.getId(), e.getNombreComercial(), e.getRazonSocial(), e.getNit(), e.getDireccion(),
                e.getTelefonoContacto(), e.getEmailContacto(), e.getLogoUrl(), e.getMonedaSimbolo(), e.getUpdatedAt());
    }
}
