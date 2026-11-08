package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.application.port.CurrentActorPort;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ObtenerMiUsuarioIdUseCase {
    private final CurrentActorPort currentActor;
    private final UsuarioRepository usuarios;

    public ObtenerMiUsuarioIdUseCase(CurrentActorPort currentActor, UsuarioRepository usuarios) {
        this.currentActor = currentActor;
        this.usuarios = usuarios;
    }

    @Transactional(readOnly = true)
    public UUID ejecutar() {
        String subject = currentActor.actual().keycloakId().filter(s -> !s.isBlank())
                .orElseThrow(JwtSubjectRequiredException::new);
        return usuarios.buscarPorKeycloakId(subject).filter(Usuario::isActivo).map(Usuario::getId)
                .orElseThrow(() -> new ResourceNotFoundException("Identidad de usuario no disponible"));
    }

    /** Controller-local mapping; other authorization errors retain framework semantics. */
    public static final class JwtSubjectRequiredException extends AccessDeniedException {
        public JwtSubjectRequiredException() {
            super("Se requiere un JWT con subject");
        }
    }
}
