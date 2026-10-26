package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.domain.model.AuditLog;
import com.dentalplatform.erp.configsecurity.domain.model.Rol;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.AuditLogRepository;
import com.dentalplatform.erp.configsecurity.domain.repository.RolRepository;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Read-only queries over users, roles and the audit trail.
 */
@Service
@Transactional(readOnly = true)
public class ConsultarSeguridadUseCase {

    static final int MAX_AUDIT_LOGS = 500;

    private final UsuarioRepository usuarios;
    private final RolRepository roles;
    private final AuditLogRepository auditLogs;

    public ConsultarSeguridadUseCase(UsuarioRepository usuarios, RolRepository roles, AuditLogRepository auditLogs) {
        this.usuarios = usuarios;
        this.roles = roles;
        this.auditLogs = auditLogs;
    }

    public List<Usuario> listarUsuarios() {
        return usuarios.listar();
    }

    public List<Rol> listarRoles() {
        return roles.listar();
    }

    public List<AuditLog> listarAuditLogs(int limite) {
        return auditLogs.ultimos(Math.clamp(limite, 1, MAX_AUDIT_LOGS));
    }
}
