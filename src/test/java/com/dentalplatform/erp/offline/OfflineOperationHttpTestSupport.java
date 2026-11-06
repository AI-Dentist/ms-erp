package com.dentalplatform.erp.offline;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
abstract class OfflineOperationHttpTestSupport {
    @LocalServerPort int port;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean(name = "clock") Clock clock;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        OfflineSyncHttpSupport.configure(registry, "erp_offline_operations");
    }

    @BeforeEach
    void resetClock() {
        setTime(Instant.now().truncatedTo(ChronoUnit.SECONDS));
    }

    void setTime(Instant now) {
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    record Session(OfflineSyncHttpSupport.Identity actor, String role, UUID id, Instant auth, Instant expiry) {
        String token() {
            return token(auth.getEpochSecond(), expiry);
        }
        String token(Object authenticationTime, Instant tokenExpiry) {
            return OfflineSyncHttpSupport.token(actor, role, authenticationTime, Instant.now(), tokenExpiry);
        }
    }

    Session session(String role, boolean confirm) throws Exception {
        var actor = OfflineSyncHttpSupport.createUser(port, role);
        JsonNode challenge = OfflineSyncHttpSupport.success(port, """
                mutation($input: PrepararSyncOfflineInput!) {
                  prepararSyncOffline(input:$input) { sessionId reauthNotBefore }
                }
                """, Map.of("input", Map.of("workspaceId", UUID.randomUUID().toString(),
                "deviceId", UUID.randomUUID().toString(), "expectedActorId", actor.id())),
                OfflineSyncHttpSupport.token(actor, role)).path("prepararSyncOffline");
        Instant auth = Instant.parse(challenge.path("reauthNotBefore").asText());
        setTime(auth);
        var session = new Session(actor, role, UUID.fromString(challenge.path("sessionId").asText()),
                auth, Instant.now().plusSeconds(900).truncatedTo(ChronoUnit.SECONDS));
        if (confirm) {
            OfflineSyncHttpSupport.success(port, """
                    mutation($id:UUID!) { confirmarSyncOffline(sessionId:$id) { sessionId } }
                    """, Map.of("id", session.id().toString()), session.token());
        }
        return session;
    }

    void changeUser(Session session, String role, boolean active) throws Exception {
        OfflineSyncHttpSupport.success(port, """
                mutation($id:UUID!, $input:ActualizarUsuarioInput!) {
                  actualizarUsuario(id:$id,input:$input) { id }
                }
                """, Map.of("id", session.actor().id(),
                "input", Map.of("rolCodigo", role, "activo", active)), OfflineSyncHttpSupport.admin());
    }
}
