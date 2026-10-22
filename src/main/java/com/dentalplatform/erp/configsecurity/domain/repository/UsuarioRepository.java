package com.dentalplatform.erp.configsecurity.domain.repository;

import com.dentalplatform.erp.configsecurity.domain.model.Usuario;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UsuarioRepository {

    List<Usuario> listar();

    Optional<Usuario> buscarPorId(UUID id);

    /** Resolves the user behind an authenticated principal (Keycloak subject first, then username). */
    Optional<Usuario> buscarPorKeycloakIdOUsername(String keycloakId, String username);

    boolean existeUsername(String username);

    boolean existeEmail(String email);

    Usuario guardar(Usuario usuario);
}
