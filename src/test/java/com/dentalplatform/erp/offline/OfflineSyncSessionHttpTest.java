package com.dentalplatform.erp.offline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class OfflineSyncSessionHttpTest {
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

    @BeforeEach
    void resetClock() {
        now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        setClock(now);
    }

    void setClock(Instant instant) {
        when(clock.instant()).thenReturn(instant);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
    }

    private static final String CONFIRM = """
            mutation($id: UUID!) { confirmarSyncOffline(sessionId: $id) {
              sessionId actorId workspaceId deviceId authenticatedAt expiresAt
            } }
            """;

    JsonNode prepare(OfflineSyncHttpSupport.Identity actor, String expected) throws Exception {
        return OfflineSyncHttpSupport.success(port, PREPARE, Map.of("input", Map.of(
                "workspaceId", UUID.randomUUID().toString(), "deviceId", UUID.randomUUID().toString(),
                "expectedActorId", expected)), OfflineSyncHttpSupport.token(actor)).path("prepararSyncOffline");
    }

    String proof(OfflineSyncHttpSupport.Identity actor, Object auth, Instant exp) {
        return OfflineSyncHttpSupport.token(actor, auth, Instant.now(), exp);
    }

    JsonNode confirm(JsonNode challenge, String token) throws Exception {
        return OfflineSyncHttpSupport.success(port, CONFIRM, Map.of("id", challenge.path("sessionId").asText()),
                token).path("confirmarSyncOffline");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        OfflineSyncHttpSupport.configure(registry, "erp_offline_sync_session");
    }

    @Test
    void linkedAdminCanPrepareBoundChallengeWithoutBusinessWrites() throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        String token = OfflineSyncHttpSupport.token(actor);
        UUID workspace = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        JsonNode before = OfflineSyncHttpSupport.snapshot(port);
        Instant started = now;
        JsonNode challenge = OfflineSyncHttpSupport.success(port, PREPARE, Map.of("input", Map.of(
                "workspaceId", workspace.toString(), "deviceId", device.toString(),
                "expectedActorId", actor.id())), token).path("prepararSyncOffline");
        Instant finished = now;

        assertThat(UUID.fromString(challenge.path("sessionId").asText())).isNotNull();
        assertThat(challenge.path("actorId").asText()).isEqualTo(actor.id());
        assertThat(challenge.path("workspaceId").asText()).isEqualTo(workspace.toString());
        assertThat(challenge.path("deviceId").asText()).isEqualTo(device.toString());
        Instant boundary = Instant.parse(challenge.path("reauthNotBefore").asText());
        assertThat(boundary).isBetween(started.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1),
                finished.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1));
        assertThat(boundary.getNano()).isZero();
        Instant expires = Instant.parse(challenge.path("expiresAt").asText());
        assertThat(expires).isBetween(started.plus(24, ChronoUnit.HOURS), finished.plus(24, ChronoUnit.HOURS));
        assertThat(challenge.path("cashflowBalanceBs").isNull()).isFalse();
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void freshLoginConfirmsChallengeAndPinsTokenExpiry() throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        JsonNode challenge = prepare(actor, actor.id());
        Instant boundary = Instant.parse(challenge.path("reauthNotBefore").asText());
        setClock(boundary);
        Instant exp = Instant.now().plusSeconds(240).truncatedTo(ChronoUnit.SECONDS);
        JsonNode result = confirm(challenge, proof(actor, boundary.getEpochSecond(), exp));
        for (String field : new String[]{"sessionId", "actorId", "workspaceId", "deviceId"}) {
            assertThat(result.path(field).asText()).as(field).isEqualTo(challenge.path(field).asText());
        }
        assertThat(Instant.parse(result.path("authenticatedAt").asText())).isEqualTo(boundary);
        assertThat(Instant.parse(result.path("expiresAt").asText())).isEqualTo(exp);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "old", "future", "fractional", "fractionalTiny", "refreshed"})
    void rejectsNonFreshAuthenticationProof(String scenario) throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        JsonNode challenge = prepare(actor, actor.id());
        Instant boundary = Instant.parse(challenge.path("reauthNotBefore").asText());
        setClock(boundary.plusSeconds(301));
        Object auth = switch (scenario) {
            case "missing" -> null;
            case "old" -> boundary.getEpochSecond();
            case "future" -> boundary.plusSeconds(302).getEpochSecond();
            case "fractional" -> boundary.plusSeconds(301).getEpochSecond() + 0.5;
            case "fractionalTiny" -> new java.math.BigDecimal(boundary.plusSeconds(301).getEpochSecond() + ".00000001");
            case "refreshed" -> boundary.minusSeconds(1).getEpochSecond();
            default -> throw new IllegalArgumentException(scenario);
        };
        JsonNode before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, CONFIRM, Map.of("id", challenge.path("sessionId").asText()),
                proof(actor, auth, Instant.now().plusSeconds(600)), null, null);
        OfflineSyncHttpSupport.error(response, "FRESH_LOGIN_REQUIRED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void actorMismatchIsRejectedBeforePrepareAndConfirm() throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        var other = OfflineSyncHttpSupport.createAdmin(port);
        JsonNode before = OfflineSyncHttpSupport.snapshot(port);
        var wrongExpected = OfflineSyncHttpSupport.send(port, PREPARE, Map.of("input", Map.of(
                "workspaceId", UUID.randomUUID().toString(), "deviceId", UUID.randomUUID().toString(),
                "expectedActorId", other.id())), OfflineSyncHttpSupport.token(actor), null, null);
        OfflineSyncHttpSupport.error(wrongExpected, "SESSION_ACTOR_MISMATCH");
        JsonNode challenge = prepare(actor, actor.id());
        Instant boundary = Instant.parse(challenge.path("reauthNotBefore").asText());
        setClock(boundary);
        var wrongActor = OfflineSyncHttpSupport.send(port, CONFIRM, Map.of("id", challenge.path("sessionId").asText()),
                proof(other, boundary.getEpochSecond(), Instant.now().plusSeconds(240)), null, null);
        OfflineSyncHttpSupport.error(wrongActor, "SESSION_ACTOR_MISMATCH");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"inactive", "role"})
    void currentDatabaseIdentityAndRoleAreRequired(String change) throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        JsonNode challenge = prepare(actor, actor.id());
        String input = change.equals("inactive") ? "rolCodigo: \"ADMIN\", activo: false" : "rolCodigo: \"CAJERO\", activo: true";
        OfflineSyncHttpSupport.success(port, "mutation { actualizarUsuario(id: \"" + actor.id()
                + "\", input: {" + input + "}) { id } }", Map.of(), OfflineSyncHttpSupport.admin());
        Instant boundary = Instant.parse(challenge.path("reauthNotBefore").asText());
        setClock(boundary);
        JsonNode before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, CONFIRM, Map.of("id", challenge.path("sessionId").asText()),
                proof(actor, boundary.getEpochSecond(), Instant.now().plusSeconds(240)), null, null);
        OfflineSyncHttpSupport.error(response, change.equals("inactive") ? "ACTOR_UNAVAILABLE" : "PERMISSION_REVOKED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void unlinkedSubjectCannotPrepareEvenWhenUsernameMatches() throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        var unlinked = new OfflineSyncHttpSupport.Identity(actor.id(), actor.username(), "unlinked-" + UUID.randomUUID());
        JsonNode before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, PREPARE, Map.of("input", Map.of(
                "workspaceId", UUID.randomUUID().toString(), "deviceId", UUID.randomUUID().toString(),
                "expectedActorId", actor.id())), OfflineSyncHttpSupport.token(unlinked), null, null);
        OfflineSyncHttpSupport.error(response, "ACTOR_UNAVAILABLE");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void activeSessionCannotBeReboundToDifferentFreshLogin() throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        JsonNode challenge = prepare(actor, actor.id());
        Instant boundary = Instant.parse(challenge.path("reauthNotBefore").asText());
        setClock(boundary.plusSeconds(1));
        Instant exp = Instant.now().plusSeconds(240).truncatedTo(ChronoUnit.SECONDS);
        JsonNode original = confirm(challenge, proof(actor, boundary.getEpochSecond(), exp));
        JsonNode before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, CONFIRM, Map.of("id", challenge.path("sessionId").asText()),
                proof(actor, boundary.plusSeconds(1).getEpochSecond(), exp), null, null);
        OfflineSyncHttpSupport.error(response, "SESSION_ALREADY_BOUND");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
        assertThat(confirm(challenge, proof(actor, boundary.getEpochSecond(), exp))).isEqualTo(original);
    }

    @Test
    void proofExactly300SecondsOldIsAccepted() throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        JsonNode challenge = prepare(actor, actor.id());
        Instant boundary = Instant.parse(challenge.path("reauthNotBefore").asText());
        setClock(boundary.plusSeconds(300));
        Instant exp = Instant.now().plusSeconds(600).truncatedTo(ChronoUnit.SECONDS);
        assertThat(Instant.parse(confirm(challenge, proof(actor, boundary.getEpochSecond(), exp))
                .path("authenticatedAt").asText())).isEqualTo(boundary);
    }

    @Test
    void reconfirmCannotExtendProofDeadlineAndExpiredSessionCannotBeRevived() throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        JsonNode challenge = prepare(actor, actor.id());
        Instant boundary = Instant.parse(challenge.path("reauthNotBefore").asText());
        setClock(boundary);
        Instant exp = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
        JsonNode original = confirm(challenge, proof(actor, boundary.getEpochSecond(), exp));
        JsonNode again = confirm(challenge, proof(actor, boundary.getEpochSecond(), exp.plusSeconds(300)));
        assertThat(again.path("expiresAt").asText()).isEqualTo(original.path("expiresAt").asText());
        setClock(exp.plusSeconds(1));
        JsonNode before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, CONFIRM, Map.of("id", challenge.path("sessionId").asText()),
                proof(actor, boundary.getEpochSecond(), exp.plusSeconds(300)), null, null);
        OfflineSyncHttpSupport.error(response, "SESSION_EXPIRED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }
}
