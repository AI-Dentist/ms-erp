package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.application.command.CrearUsuarioCommand;
import com.dentalplatform.erp.configsecurity.application.port.PasswordVerificationPort;
import com.dentalplatform.erp.configsecurity.application.port.ProvisioningSubjectLockPort;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.mastercatalog.application.usecase.VincularIdentidadOdontologoUseCase;
import com.dentalplatform.erp.mastercatalog.domain.repository.OdontologoRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class CrearUsuarioConIdentidadUseCase {
    private final CrearUsuarioUseCase crear;
    private final VincularIdentidadUsuarioUseCase vincularUsuario;
    private final VincularIdentidadOdontologoUseCase vincularOdontologo;
    private final UsuarioRepository usuarios;
    private final OdontologoRepository odontologos;
    private final ProvisioningSubjectLockPort subjects;
    private final PasswordVerificationPort passwords;

    public CrearUsuarioConIdentidadUseCase(CrearUsuarioUseCase crear,
            VincularIdentidadUsuarioUseCase vincularUsuario, VincularIdentidadOdontologoUseCase vincularOdontologo,
            UsuarioRepository usuarios, OdontologoRepository odontologos,
            ProvisioningSubjectLockPort subjects, PasswordVerificationPort passwords) {
        this.crear = crear;
        this.vincularUsuario = vincularUsuario;
        this.vincularOdontologo = vincularOdontologo;
        this.usuarios = usuarios;
        this.odontologos = odontologos;
        this.subjects = subjects;
        this.passwords = passwords;
    }

    @Transactional
    public Usuario ejecutar(CrearUsuarioCommand command, String subject, UUID odontologoId) {
        if (subject == null || subject.isBlank() || subject.codePointCount(0, subject.length()) > 100) {
            throw new BusinessRuleException("El subject de Keycloak debe tener entre 1 y 100 caracteres y no estar en blanco");
        }
        Usuario.validarPassword(command.password());
        // There is no user row to lock for a new subject. Keep this lock through creation/link/audit commit.
        subjects.bloquear(subject);
        var owner = usuarios.buscarPorKeycloakId(subject);
        if (owner.isPresent()) return repetir(owner.get().getId(), command, subject, odontologoId);

        Usuario nuevo = crear.ejecutar(command);
        if (odontologoId == null) {
            vincularUsuario.ejecutar(nuevo.getId(), subject);
        } else {
            // The paired helper rejects partial links: never pre-link the user on the dentist path.
            vincularOdontologo.ejecutar(nuevo.getId(), odontologoId, subject);
        }
        return usuarios.buscarPorId(nuevo.getId())
                .orElseThrow(() -> new IllegalStateException("No se pudo recuperar el usuario creado"));
    }

    private Usuario repetir(UUID id, CrearUsuarioCommand command, String subject, UUID odontologoId) {
        Usuario actual = usuarios.bloquearPorId(id)
                .orElseThrow(() -> new ResourceNotFoundException("Usuario no encontrado"));
        if (!actual.isActivo()) throw new BusinessRuleException("El usuario está inactivo");
        // Reuse domain validation without persisting a candidate or storing a credential fingerprint.
        Usuario candidate = Usuario.crear(actual.getRol(), normalized(command.username()), command.password(),
                actual.getPasswordHash(), normalized(command.email()), command.nombreCompleto(), actual.getCreatedAt());
        if (!subject.equals(actual.getKeycloakId()) || !actual.getRol().codigo().equals(command.rolCodigo())
                || !actual.getUsername().equals(candidate.getUsername()) || !actual.getEmail().equals(candidate.getEmail())
                || !actual.getNombreCompleto().equals(candidate.getNombreCompleto())
                || !passwords.matches(command.password(), actual.getPasswordHash())) throw conflict();

        var linkedDoctor = odontologos.buscarPorUsuarioId(id);
        if (odontologoId == null) {
            if (linkedDoctor.isPresent()) throw conflict();
        } else {
            var doctor = odontologos.bloquearPorId(odontologoId)
                    .orElseThrow(() -> new ResourceNotFoundException("Odontólogo no encontrado"));
            if (!doctor.isActivo()) throw new BusinessRuleException("El odontólogo está inactivo");
            if (!id.equals(doctor.getUsuarioId())
                    || linkedDoctor.filter(d -> odontologoId.equals(d.getId())).isEmpty()) throw conflict();
        }
        return actual;
    }

    private static String normalized(String value) {
        return value == null ? null : value.trim().toLowerCase(Locale.ROOT);
    }

    private static BusinessRuleException conflict() {
        return new BusinessRuleException("La identidad de usuario ya está vinculada");
    }
}
