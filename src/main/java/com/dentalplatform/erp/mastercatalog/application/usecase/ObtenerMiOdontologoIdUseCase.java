package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.port.CurrentActorPort;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.mastercatalog.domain.repository.OdontologoRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class ObtenerMiOdontologoIdUseCase {

    private final CurrentActorPort currentActor;
    private final UsuarioRepository usuarios;
    private final OdontologoRepository odontologos;

    public ObtenerMiOdontologoIdUseCase(CurrentActorPort currentActor, UsuarioRepository usuarios,
                                       OdontologoRepository odontologos) {
        this.currentActor = currentActor;
        this.usuarios = usuarios;
        this.odontologos = odontologos;
    }

    @Transactional(readOnly = true)
    public UUID ejecutar() {
        String subject = currentActor.actual().keycloakId().filter(s -> !s.isBlank())
                .orElseThrow(JwtSubjectRequiredException::new);
        Usuario usuario = usuarios.buscarPorKeycloakId(subject).filter(Usuario::isActivo)
                .orElseThrow(ObtenerMiOdontologoIdUseCase::unavailable);
        return odontologos.buscarPorUsuarioId(usuario.getId())
                .filter(Odontologo::isActivo)
                .filter(o -> usuario.getId().equals(o.getUsuarioId()))
                .map(Odontologo::getId)
                .orElseThrow(ObtenerMiOdontologoIdUseCase::unavailable);
    }

    private static ResourceNotFoundException unavailable() {
        // Do not expose which part of another account's association is missing or inactive.
        return new ResourceNotFoundException("Identidad odontológica no disponible");
    }

    /** Handled only by the identity controller; other authorization errors retain framework semantics. */
    public static final class JwtSubjectRequiredException extends AccessDeniedException {
        public JwtSubjectRequiredException() {
            super("Se requiere un JWT con subject");
        }
    }
}
