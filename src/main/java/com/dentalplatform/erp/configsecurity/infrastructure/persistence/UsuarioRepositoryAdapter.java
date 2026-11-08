package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class UsuarioRepositoryAdapter implements UsuarioRepository {

    private final UsuarioJpaRepository repository;
    private final RolJpaRepository roles;

    UsuarioRepositoryAdapter(UsuarioJpaRepository repository, RolJpaRepository roles) {
        this.repository = repository;
        this.roles = roles;
    }

    @Override
    public List<Usuario> listar() {
        return repository.findAllByOrderByUsernameAsc().stream().map(UsuarioRepositoryAdapter::toDomain).toList();
    }

    @Override
    public Optional<Usuario> buscarPorId(UUID id) {
        return repository.findById(id).map(UsuarioRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Usuario> buscarPorKeycloakId(String keycloakId) {
        return repository.findByKeycloakId(keycloakId).map(UsuarioRepositoryAdapter::toDomain);
    }

    @Override
    public Optional<Usuario> bloquearPorId(UUID id) {
        return repository.bloquearPorId(id).map(UsuarioRepositoryAdapter::toDomain);
    }

    @Override
    public void vincularKeycloakId(UUID id, String keycloakId) {
        if (repository.vincularKeycloakId(id, keycloakId) != 1) {
            throw new BusinessRuleException("La identidad odontológica ya está vinculada");
        }
    }

    @Override
    public Optional<Usuario> buscarPorKeycloakIdOUsername(String keycloakId, String username) {
        Optional<UsuarioJpaEntity> found = keycloakId == null ? Optional.empty() : repository.findByKeycloakId(keycloakId);
        if (found.isEmpty() && username != null) {
            found = repository.findByUsername(username);
        }
        return found.map(UsuarioRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existeUsername(String username) {
        return repository.existsByUsername(username);
    }

    @Override
    public boolean existeEmail(String email) {
        return repository.existsByEmail(email);
    }

    @Override
    public boolean existeEmailDeOtro(String email, UUID id) {
        return repository.existsByEmailAndIdNot(email, id);
    }

    @Override
    public List<Usuario> bloquearAdminsActivos() {
        return repository.bloquearAdminsActivos().stream().map(UsuarioRepositoryAdapter::toDomain).toList();
    }

    @Override
    public Usuario guardar(Usuario u) {
        RolJpaEntity rol = roles.getReferenceById(u.getRol().id());
        return toDomain(repository.save(new UsuarioJpaEntity(u.getId(), u.getKeycloakId(), rol, u.getUsername(),
                u.getPasswordHash(), u.getEmail(), u.getNombreCompleto(), u.isActivo(), u.getCreatedAt())));
    }

    private static Usuario toDomain(UsuarioJpaEntity e) {
        return new Usuario(e.getId(), e.getKeycloakId(), RolRepositoryAdapter.toDomain(e.getRol()), e.getUsername(),
                e.getPasswordHash(), e.getEmail(), e.getNombreCompleto(), e.isActivo(), e.getCreatedAt());
    }
}
