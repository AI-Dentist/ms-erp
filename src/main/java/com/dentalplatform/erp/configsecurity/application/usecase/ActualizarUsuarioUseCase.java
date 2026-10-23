package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.application.command.ActualizarUsuarioCommand;
import com.dentalplatform.erp.configsecurity.application.port.CurrentActorPort;
import com.dentalplatform.erp.configsecurity.domain.model.Rol;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.RolRepository;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Changes a user's role, active flag, name and email. The active administrators are locked first so two concurrent
 * changes cannot both pass the "at least one active ADMIN" rule.
 */
@Service
public class ActualizarUsuarioUseCase {

    private final UsuarioRepository usuarios;
    private final RolRepository roles;
    private final CurrentActorPort currentActor;
    private final RegistrarAuditoriaUseCase auditoria;

    public ActualizarUsuarioUseCase(UsuarioRepository usuarios, RolRepository roles, CurrentActorPort currentActor,
                                    RegistrarAuditoriaUseCase auditoria) {
        this.usuarios = usuarios;
        this.roles = roles;
        this.currentActor = currentActor;
        this.auditoria = auditoria;
    }

    @Transactional
    public Usuario ejecutar(ActualizarUsuarioCommand command) {
        List<Usuario> adminsActivos = usuarios.bloquearAdminsActivos();
        Usuario actual = usuarios.buscarPorId(command.id())
                .orElseThrow(() -> new ResourceNotFoundException("Usuario", command.id()));
        Rol rol = roles.buscarPorCodigo(command.rolCodigo())
                .orElseThrow(() -> new BusinessRuleException("No existe el rol " + command.rolCodigo()));
        String email = command.email() == null ? null : command.email().trim().toLowerCase(Locale.ROOT);
        if (email != null && usuarios.existeEmailDeOtro(email, actual.getId())) {
            throw new BusinessRuleException("Ya existe un usuario con el email " + email);
        }
        long otrosAdmins = adminsActivos.stream().filter(a -> !a.getId().equals(actual.getId())).count();
        Usuario editado = actual.actualizar(actorId(), rol, command.activo(), command.nombreCompleto(), email,
                otrosAdmins);
        Usuario guardado = usuarios.guardar(editado);
        boolean desactivado = actual.isActivo() && !guardado.isActivo();
        auditoria.registrar(desactivado ? "USUARIO_DESACTIVADO" : "USUARIO_ACTUALIZADO", "usuarios",
                guardado.getId(), Map.of("username", guardado.getUsername(), "rol", rol.codigo(),
                        "activo", guardado.isActivo()));
        return guardado;
    }

    private UUID actorId() {
        CurrentActorPort.Actor actor = currentActor.actual();
        return usuarios.buscarPorKeycloakIdOUsername(actor.keycloakId().orElse(null), actor.username())
                .map(Usuario::getId).orElse(null);
    }
}
