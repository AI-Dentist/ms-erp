package com.dentalplatform.erp.financebilling.application.agent;

import com.dentalplatform.erp.configsecurity.application.port.CurrentActorPort;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Who is calling, resolved from the security context of the current thread (never from the model or the prompt). */
@Component
public class AgentActorResolver {
    private final CurrentActorPort actor;
    private final UsuarioRepository usuarios;

    public AgentActorResolver(CurrentActorPort actor, UsuarioRepository usuarios) {
        this.actor = actor;
        this.usuarios = usuarios;
    }

    public String solicitante() { return actor.actual().username(); }

    public Optional<UUID> usuarioId() {
        var current = actor.actual();
        return usuarios.buscarPorKeycloakIdOUsername(current.keycloakId().orElse(null), current.username())
                .map(Usuario::getId);
    }
}
