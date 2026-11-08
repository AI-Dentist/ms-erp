package com.dentalplatform.erp.offline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class OfflineSyncPreparationHttpTest {
    private static final String PREPARE = """
            mutation($input: PrepararSyncOfflineInput!) {
              prepararSyncOffline(input: $input) {
                sessionId actorId workspaceId deviceId reauthNotBefore expiresAt cashflowBalanceBs
              }
            }
            """;
    @LocalServerPort int port;
    @MockitoBean RabbitTemplate rabbit;
    @MockitoBean(name = "clock") Clock clock;
    Instant now;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        OfflineSyncHttpSupport.configure(registry, "erp_offline_sync_prepare");
    }

    @BeforeEach
    void setClock() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS).plusMillis(123);
        when(clock.instant()).thenReturn(now);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private Map<String, Object> input(String actor, UUID workspace, UUID device) {
        return Map.of("input", Map.of("expectedActorId", actor, "workspaceId", workspace.toString(),
                "deviceId", device.toString()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CAJERO", "RECEPCIONISTA"})
    void prepareBindsContextWithoutBusinessWrites(String role) throws Exception {
        var actor = OfflineSyncHttpSupport.createUser(port, role);
        var before = OfflineSyncHttpSupport.snapshot(port);
        UUID workspace = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        var result = OfflineSyncHttpSupport.success(port, PREPARE, input(actor.id(), workspace, device),
                OfflineSyncHttpSupport.token(actor, role)).path("prepararSyncOffline");
        assertThat(UUID.fromString(result.path("sessionId").asText())).isNotNull();
        assertThat(result.path("actorId").asText()).isEqualTo(actor.id());
        assertThat(result.path("workspaceId").asText()).isEqualTo(workspace.toString());
        assertThat(result.path("deviceId").asText()).isEqualTo(device.toString());
        assertThat(Instant.parse(result.path("reauthNotBefore").asText()))
                .isEqualTo(now.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
        assertThat(Instant.parse(result.path("expiresAt").asText())).isEqualTo(now.plusSeconds(86400));
        assertThat(result.path("cashflowBalanceBs").isNull()).isEqualTo(role.equals("RECEPCIONISTA"));
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void unlinkedSubjectDoesNotFallBackToUsername() throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        var unlinked = new OfflineSyncHttpSupport.Identity(actor.id(), actor.username(), "unlinked-" + UUID.randomUUID());
        var before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, PREPARE, input(actor.id(), UUID.randomUUID(), UUID.randomUUID()),
                OfflineSyncHttpSupport.token(unlinked), null, null);
        OfflineSyncHttpSupport.error(response, "ACTOR_UNAVAILABLE");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void expectedActorMismatchIsRejected() throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        var before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, PREPARE,
                input(UUID.randomUUID().toString(), UUID.randomUUID(), UUID.randomUUID()),
                OfflineSyncHttpSupport.token(actor), null, null);
        OfflineSyncHttpSupport.error(response, "SESSION_ACTOR_MISMATCH");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }
}
