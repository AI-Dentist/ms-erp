package com.dentalplatform.erp.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineOperationStatusHttpTest extends OfflineOperationHttpTestSupport {
    private static final String STATUS = """
            query($session:UUID!,$operation:UUID!) {
              estadoOperacionOffline(sessionId:$session,operationId:$operation) {
                operationId type state payloadHash reasonCode confirmedAt
                result { patientId orderId installments { number id } movementId receiptId }
              }
            }
            """;

    private Map<String, Object> arguments(Session session, UUID operation) {
        return Map.of("session", session.id().toString(), "operation", operation.toString());
    }

    private void notFound(Session session, UUID operation, String token) throws Exception {
        var before = OfflineSyncHttpSupport.snapshot(port);
        var result = OfflineSyncHttpSupport.success(port, STATUS, arguments(session, operation), token)
                .path("estadoOperacionOffline");
        assertThat(result.path("operationId").asText()).isEqualTo(operation.toString());
        assertThat(result.path("state").asText()).isEqualTo("NOT_FOUND");
        for (String field : new String[]{"type", "payloadHash", "reasonCode", "confirmedAt", "result"}) {
            assertThat(result.path(field).isNull()).as(field).isTrue();
        }
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "CAJERO", "RECEPCIONISTA"})
    void liveConfirmedRolesCanInspectTheirMissingOperationWithoutBusinessWrites(String role) throws Exception {
        var session = session(role, true);
        notFound(session, UUID.randomUUID(), session.token());
    }

    @Test
    void activeProofOutlivesInitialFreshnessButNotItsPinnedExpiry() throws Exception {
        var session = session("ADMIN", true);
        setTime(session.auth().plusSeconds(301));
        notFound(session, UUID.randomUUID(), session.token());
        setTime(session.expiry().plusSeconds(1));
        var before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, STATUS, arguments(session, UUID.randomUUID()),
                session.token(session.auth().getEpochSecond(), session.expiry().plusSeconds(600)), null, null);
        OfflineSyncHttpSupport.error(response, "SESSION_EXPIRED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pending", "actor", "auth", "missing", "inactive", "role", "unlinked"})
    void authorizationPrecedesEvenNotFoundStatus(String scenario) throws Exception {
        var session = session("ADMIN", !scenario.equals("pending"));
        String token = session.token();
        String expected = switch (scenario) {
            case "pending" -> "SESSION_UNAVAILABLE";
            case "actor" -> {
                var other = OfflineSyncHttpSupport.createAdmin(port);
                token = OfflineSyncHttpSupport.token(other, session.auth().getEpochSecond(),
                        Instant.now(), session.expiry());
                yield "SESSION_ACTOR_MISMATCH";
            }
            case "auth" -> {
                token = session.token(session.auth().plusSeconds(1).getEpochSecond(), session.expiry());
                yield "SESSION_ALREADY_BOUND";
            }
            case "missing" -> {
                token = session.token(null, session.expiry());
                yield "FRESH_LOGIN_REQUIRED";
            }
            case "inactive" -> {
                changeUser(session, "ADMIN", false);
                yield "ACTOR_UNAVAILABLE";
            }
            case "role" -> {
                changeUser(session, "CAJERO", true);
                yield "PERMISSION_REVOKED";
            }
            case "unlinked" -> {
                var ghost = new OfflineSyncHttpSupport.Identity(UUID.randomUUID().toString(),
                        "ghost", "unlinked-" + UUID.randomUUID());
                token = OfflineSyncHttpSupport.token(ghost, session.auth().getEpochSecond(),
                        Instant.now(), session.expiry());
                yield "ACTOR_UNAVAILABLE";
            }
            default -> throw new IllegalArgumentException(scenario);
        };
        var before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, STATUS, arguments(session, UUID.randomUUID()),
                token, null, null);
        OfflineSyncHttpSupport.error(response, expected);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void legacyKeyIsRejectedBeforeAnyStatusCanBeReplayed() throws Exception {
        var session = session("ADMIN", true);
        var before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, STATUS, arguments(session, UUID.randomUUID()),
                session.token(), UUID.randomUUID().toString(), null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("OFFLINE_LEGACY_KEY_NOT_ALLOWED");
        assertThat(response.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }
}
