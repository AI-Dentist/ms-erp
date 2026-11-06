package com.dentalplatform.erp.configsecurity.infrastructure.offline;

import com.dentalplatform.erp.configsecurity.application.port.OfflineSyncIdentityPort;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.util.Set;
import java.util.UUID;

@Component
public class OfflineSyncIdentityAdapter implements OfflineSyncIdentityPort {
    private static final Set<String> ROLES = Set.of("ADMIN", "CAJERO", "RECEPCIONISTA");
    private final JdbcTemplate jdbc;
    private final Environment environment;

    public OfflineSyncIdentityAdapter(JdbcTemplate jdbc, Environment environment) {
        this.jdbc = jdbc;
        this.environment = environment;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Caller current() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()
                || jwt.getToken().getIssuer() == null || jwt.getToken().getSubject() == null
                || jwt.getToken().getSubject().isBlank()) {
            throw new OfflineSyncFailure("ACTOR_UNAVAILABLE");
        }
        String issuer = jwt.getToken().getIssuer().toString();
        String configuredIssuer = environment.getProperty(environment.matchesProfiles("local")
                ? "erp.security.local-dev-user.jwt-issuer-uri"
                : "spring.security.oauth2.resourceserver.jwt.issuer-uri");
        if (configuredIssuer == null || configuredIssuer.isBlank() || !issuer.equals(configuredIssuer.trim())) {
            throw new OfflineSyncFailure("ACTOR_UNAVAILABLE");
        }
        String subject = jwt.getToken().getSubject();
        var users = jdbc.query("""
                SELECT u.id, u.activo, r.codigo
                FROM usuarios u JOIN roles r ON r.id = u.rol_id
                WHERE u.keycloak_id = ?
                FOR SHARE OF u, r
                """, (rs, row) -> new User(rs.getObject("id", UUID.class),
                rs.getBoolean("activo"), rs.getString("codigo")), subject);
        if (users.size() != 1 || !users.getFirst().active()) {
            throw new OfflineSyncFailure("ACTOR_UNAVAILABLE");
        }
        User user = users.getFirst();
        if (!ROLES.contains(user.role()) || jwt.getAuthorities().stream()
                .noneMatch(authority -> authority.getAuthority().equals("ROLE_" + user.role()))) {
            throw new OfflineSyncFailure("PERMISSION_REVOKED");
        }
        // Read the validated signed payload directly: claim conversion must not coerce fractional auth_time.
        Object authTime;
        try {
            authTime = SignedJWT.parse(jwt.getToken().getTokenValue()).getPayload().toJSONObject().get("auth_time");
        } catch (ParseException | RuntimeException ex) {
            throw new OfflineSyncFailure("ACTOR_UNAVAILABLE");
        }
        return new Caller(user.id(), issuer, subject, user.role(), authTime, jwt.getToken().getExpiresAt());
    }

    private record User(UUID id, boolean active, String role) { }
}
