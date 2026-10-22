package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import com.dentalplatform.erp.configsecurity.domain.model.Permiso;
import com.dentalplatform.erp.configsecurity.domain.model.Rol;
import com.dentalplatform.erp.configsecurity.domain.repository.RolRepository;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Component
class RolRepositoryAdapter implements RolRepository {

    private final RolJpaRepository repository;

    RolRepositoryAdapter(RolJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Rol> listar() {
        return repository.findAll().stream()
                .map(RolRepositoryAdapter::toDomain)
                .sorted(Comparator.comparing(Rol::codigo))
                .toList();
    }

    @Override
    public Optional<Rol> buscarPorCodigo(String codigo) {
        return repository.findByCodigo(codigo).map(RolRepositoryAdapter::toDomain);
    }

    static Rol toDomain(RolJpaEntity e) {
        List<Permiso> permisos = e.getPermisos().stream()
                .map(p -> new Permiso(p.getId(), p.getCodigo(), p.getModulo(), p.getDescripcion()))
                .toList();
        return new Rol(e.getId(), e.getCodigo(), e.getNombre(), e.getDescripcion(), permisos);
    }
}
