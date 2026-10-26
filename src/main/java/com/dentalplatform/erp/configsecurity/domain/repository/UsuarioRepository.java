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

    /** Whether another user (not {@code id}) already uses the email. */
    boolean existeEmailDeOtro(String email, UUID id);

    /**
     * Active administrators, read with a row lock held until the transaction ends. Concurrent callers queue up and
     * re-evaluate which administrators are still active once the previous one commits.
     */
    List<Usuario> bloquearAdminsActivos();

    Usuario guardar(Usuario usuario);
}
