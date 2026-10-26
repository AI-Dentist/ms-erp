package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.application.command.CrearUsuarioCommand;
import com.dentalplatform.erp.configsecurity.application.port.PasswordHasherPort;
import com.dentalplatform.erp.configsecurity.domain.model.Rol;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.RolRepository;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.Map;

@Service
public class CrearUsuarioUseCase {

    private final UsuarioRepository usuarios;
    private final RolRepository roles;
    private final PasswordHasherPort passwordHasher;
    private final RegistrarAuditoriaUseCase auditoria;
    private final Clock clock;

    public CrearUsuarioUseCase(UsuarioRepository usuarios, RolRepository roles, PasswordHasherPort passwordHasher,
                               RegistrarAuditoriaUseCase auditoria, Clock clock) {
        this.usuarios = usuarios;
        this.roles = roles;
        this.passwordHasher = passwordHasher;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    @Transactional
    public Usuario ejecutar(CrearUsuarioCommand command) {
        Rol rol = roles.buscarPorCodigo(command.rolCodigo())
                .orElseThrow(() -> new BusinessRuleException("No existe el rol " + command.rolCodigo()));
        Usuario.validarPassword(command.password());
        String username = command.username() == null ? null : command.username().trim().toLowerCase(Locale.ROOT);
        String email = command.email() == null ? null : command.email().trim().toLowerCase(Locale.ROOT);
        if (username != null && usuarios.existeUsername(username)) {
            throw new BusinessRuleException("Ya existe un usuario con el username " + username);
        }
        if (email != null && usuarios.existeEmail(email)) {
            throw new BusinessRuleException("Ya existe un usuario con el email " + email);
        }
        Usuario nuevo = Usuario.crear(rol, username, command.password(), passwordHasher.hash(command.password()), email,
                command.nombreCompleto(), OffsetDateTime.now(clock));
        Usuario guardado = usuarios.guardar(nuevo);
        auditoria.registrar("USUARIO_CREADO", "usuarios", guardado.getId(),
                Map.of("username", guardado.getUsername(), "rol", rol.codigo()));
        return guardado;
    }
}
