package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.application.port.OfflineActivationPort;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ActivarJornadaOfflineUseCase {
    private static final Map<String, List<String>> CAPABILITIES = Map.of(
            "ADMIN", List.of("CASH", "EXPENSES", "MANUAL_ORDERS", "PATIENTS"),
            "CAJERO", List.of("CASH", "EXPENSES", "MANUAL_ORDERS"),
            "RECEPCIONISTA", List.of("MANUAL_ORDERS", "PATIENTS"));
    private final OfflineActivationPort offline;
    private final UsuarioRepository usuarios;

    public ActivarJornadaOfflineUseCase(OfflineActivationPort offline, UsuarioRepository usuarios) {
        this.offline = offline;
        this.usuarios = usuarios;
    }

    @Transactional(readOnly = true)
    public OfflineActivationPort.SignedDocument ejecutar(UUID workspaceId, UUID deviceId, int durationSeconds) {
        if (workspaceId == null || deviceId == null || durationSeconds < 1 || durationSeconds > 28800) {
            throw new BusinessRuleException("Duración o identificadores offline inválidos");
        }
        var identity = offline.authenticatedIdentity();
        Usuario user = usuarios.buscarPorKeycloakId(identity.subject()).filter(Usuario::isActivo)
                .orElseThrow(() -> new ResourceNotFoundException("Identidad de usuario no disponible"));
        String role = user.getRol().codigo();
        List<String> capabilities = CAPABILITIES.get(role);
        if (capabilities == null || !identity.authorities().contains("ROLE_" + role)) {
            throw new AccessDeniedException("Acceso offline denegado");
        }
        Instant now = Instant.now();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("version", 1);
        payload.put("grantId", UUID.randomUUID().toString());
        payload.put("audience", "erp-offline-local-only");
        payload.put("erpUserId", user.getId().toString());
        payload.put("identityIssuer", identity.issuer());
        payload.put("identitySubject", identity.subject());
        payload.put("role", role);
        payload.put("capabilities", capabilities);
        payload.put("workspaceId", workspaceId.toString());
        payload.put("deviceId", deviceId.toString());
        payload.put("issuedAt", now.toString());
        payload.put("expiresAt", now.plusSeconds(durationSeconds).toString());
        return offline.sign(payload);
    }
}
