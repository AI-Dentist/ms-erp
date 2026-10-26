package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.application.port.CurrentActorPort;
import com.dentalplatform.erp.configsecurity.domain.model.AuditLog;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.AuditLogRepository;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Public entry point other bounded contexts use to write the audit trail, inside the caller's transaction.
 */
@Service
public class RegistrarAuditoriaUseCase {

    private final AuditLogRepository auditLogs;
    private final UsuarioRepository usuarios;
    private final CurrentActorPort currentActor;
    private final JsonMapper jsonMapper;
    private final Clock clock;

    public RegistrarAuditoriaUseCase(AuditLogRepository auditLogs, UsuarioRepository usuarios,
                                     CurrentActorPort currentActor, JsonMapper jsonMapper, Clock clock) {
        this.auditLogs = auditLogs;
        this.usuarios = usuarios;
        this.currentActor = currentActor;
        this.jsonMapper = jsonMapper;
        this.clock = clock;
    }

    @Transactional
    public AuditLog registrar(String accion, String entidadAfectada, UUID entidadId, Map<String, Object> detalle) {
        CurrentActorPort.Actor actor = currentActor.actual();
        UUID usuarioId = usuarios.buscarPorKeycloakIdOUsername(actor.keycloakId().orElse(null), actor.username())
                .map(Usuario::getId)
                .orElse(null);
        String detalleJson = detalle == null || detalle.isEmpty() ? null : jsonMapper.writeValueAsString(detalle);
        return auditLogs.guardar(AuditLog.registrar(usuarioId, accion, entidadAfectada, entidadId, detalleJson,
                actor.ipOrigen().orElse(null), OffsetDateTime.now(clock)));
    }
}
