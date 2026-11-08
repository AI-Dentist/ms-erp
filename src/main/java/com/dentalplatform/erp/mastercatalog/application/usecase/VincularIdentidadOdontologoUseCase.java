package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.mastercatalog.domain.repository.OdontologoRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

@Service
public class VincularIdentidadOdontologoUseCase {

    private final UsuarioRepository usuarios;
    private final OdontologoRepository odontologos;
    private final RegistrarAuditoriaUseCase auditoria;

    public VincularIdentidadOdontologoUseCase(UsuarioRepository usuarios, OdontologoRepository odontologos,
                                             RegistrarAuditoriaUseCase auditoria) {
        this.usuarios = usuarios;
        this.odontologos = odontologos;
        this.auditoria = auditoria;
    }

    @Transactional
    public UUID ejecutar(UUID usuarioId, UUID odontologoId, String keycloakSubject) {
        if (keycloakSubject == null || keycloakSubject.isBlank()
                || keycloakSubject.codePointCount(0, keycloakSubject.length()) > 100) {
            throw new BusinessRuleException("El subject de Keycloak debe tener entre 1 y 100 caracteres y no estar en blanco");
        }
        // Fixed order: requested user, then requested dentist. Ownership checks do not acquire other row locks.
        Usuario usuario = usuarios.bloquearPorId(usuarioId)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        Odontologo odontologo = odontologos.bloquearPorId(odontologoId)
                .orElseThrow(() -> new ResourceNotFoundException("Odontólogo no encontrado"));
        if (!usuario.isActivo()) {
            throw new BusinessRuleException("El usuario está inactivo");
        }
        if (!odontologo.isActivo()) {
            throw new BusinessRuleException("El odontólogo está inactivo");
        }
        var subjectOwner = usuarios.buscarPorKeycloakId(keycloakSubject);
        var linkedDoctor = odontologos.buscarPorUsuarioId(usuarioId);
        boolean consistent = keycloakSubject.equals(usuario.getKeycloakId())
                && usuarioId.equals(odontologo.getUsuarioId())
                && subjectOwner.filter(u -> usuarioId.equals(u.getId())).isPresent()
                && linkedDoctor.filter(o -> odontologoId.equals(o.getId())).isPresent();
        if (consistent) {
            return odontologoId; // No UPDATE, audit record or catalog event on a consistent repeat.
        }
        if (usuario.getKeycloakId() != null || odontologo.getUsuarioId() != null
                || subjectOwner.isPresent() || linkedDoctor.isPresent()) {
            // Partial legacy links are not repaired or overwritten by this operation either.
            throw new BusinessRuleException("La identidad odontológica ya está vinculada");
        }
        // Conditional writes plus the existing UNIQUE constraints arbitrate competing subject/owner claims.
        // Any failure rolls back both links and the audit record in the same transaction.
        usuarios.vincularKeycloakId(usuarioId, keycloakSubject);
        odontologos.vincularUsuarioId(odontologoId, usuarioId);
        auditoria.registrar("IDENTIDAD_ODONTOLOGO_VINCULADA", "odontologos", odontologoId,
                Map.of("usuarioId", usuarioId, "keycloakSubject", keycloakSubject));
        return odontologoId;
    }
}
