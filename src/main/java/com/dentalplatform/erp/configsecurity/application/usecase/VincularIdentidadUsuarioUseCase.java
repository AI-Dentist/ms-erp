package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class VincularIdentidadUsuarioUseCase {
    private final UsuarioRepository usuarios;
    private final RegistrarAuditoriaUseCase auditoria;

    public VincularIdentidadUsuarioUseCase(UsuarioRepository usuarios, RegistrarAuditoriaUseCase auditoria) {
        this.usuarios = usuarios;
        this.auditoria = auditoria;
    }

    @Transactional
    public UUID ejecutar(UUID usuarioId, String keycloakSubject) {
        if (keycloakSubject == null || keycloakSubject.isBlank()
                || keycloakSubject.codePointCount(0, keycloakSubject.length()) > 100) {
            throw new BusinessRuleException("El subject de Keycloak debe tener entre 1 y 100 caracteres y no estar en blanco");
        }
        Usuario usuario = usuarios.bloquearPorId(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        if (!usuario.isActivo()) {
            throw new BusinessRuleException("El usuario está inactivo");
        }
        var owner = usuarios.buscarPorKeycloakId(keycloakSubject);
        if (keycloakSubject.equals(usuario.getKeycloakId())
                && owner.filter(u -> usuarioId.equals(u.getId())).isPresent()) {
            return usuarioId; // Exact repeat: no UPDATE and no additional audit.
        }
        if (usuario.getKeycloakId() != null || owner.isPresent()) {
            throw new BusinessRuleException("La identidad de usuario ya está vinculada");
        }
        // Existing conditional UPDATE and UNIQUE constraint arbitrate competing subject claims.
        // A failed claim rolls back the whole transaction, including its audit.
        usuarios.vincularKeycloakId(usuarioId, keycloakSubject);
        auditoria.registrar("IDENTIDAD_USUARIO_VINCULADA", "usuarios", usuarioId,
                Map.of("keycloakSubject", keycloakSubject));
        return usuarioId;
    }
}
