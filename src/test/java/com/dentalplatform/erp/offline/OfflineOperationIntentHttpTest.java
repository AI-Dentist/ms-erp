package com.dentalplatform.erp.offline;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;

import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OfflineOperationIntentHttpTest extends OfflineOperationHttpTestSupport {
    static final String FIELDS = "operationId type state payloadHash reasonCode confirmedAt result { patientId }";
    static final String SYNC = "mutation($input:OfflineOperationInput!){sincronizarOperacionOffline(input:$input){"
            + FIELDS + "}}";
    static final String STATUS = "query($session:UUID!,$op:UUID!){estadoOperacionOffline(sessionId:$session,operationId:$op){"
            + FIELDS + "}}";

    static Map<String, Object> patient() {
        return Map.of("ciNit", "SYN-1", "nombres", " Ana ", "apellidos", "Munoz",
                "telefonoWhatsapp", "+59170012345");
    }

    Map<String, Object> input(Session session, UUID op, Map<String, Object> patient) {
        return Map.of("sessionId", session.id().toString(), "operationId", op.toString(),
                "type", "PATIENT_CREATE", "patient", patient);
    }

    JsonNode sync(Session session, Map<String, Object> input) throws Exception {
        return OfflineSyncHttpSupport.success(port, SYNC, Map.of("input", input), session.token())
                .path("sincronizarOperacionOffline");
    }

    JsonNode status(Session session, UUID op) throws Exception {
        return OfflineSyncHttpSupport.success(port, STATUS, Map.of("session", session.id().toString(),
                "op", op.toString()), session.token()).path("estadoOperacionOffline");
    }

    @Test
    void frozenIntentIsPreparedNotRegisteredAndMatchesIndependentHashVector() throws Exception {
        var session = session("ADMIN", true);
        UUID op = UUID.randomUUID();
        var before = OfflineSyncHttpSupport.snapshot(port);
        JsonNode prepared = sync(session, input(session, op, patient()));
        assertThat(prepared.path("operationId").asText()).isEqualTo(op.toString());
        assertThat(prepared.path("type").asText()).isEqualTo("PATIENT_CREATE");
        assertThat(prepared.path("state").asText()).isEqualTo("PREPARED");
        assertThat(prepared.path("payloadHash").asText())
                .isEqualTo("1b4c2532beb6b051ee3725959694e4c5c8e375591dc010b91ca3cb939333bb1f");
        for (String field : new String[]{"reasonCode", "confirmedAt", "result"}) {
            assertThat(prepared.path(field).isNull()).as(field).isTrue();
        }
        assertThat(sync(session, input(session, op, patient()))).isEqualTo(prepared);
        assertThat(status(session, op)).isEqualTo(prepared);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void alteredPayloadConflictsWithoutChangingTheOriginalIntentOrBusiness() throws Exception {
        var session = session("ADMIN", true);
        UUID op = UUID.randomUUID();
        JsonNode original = sync(session, input(session, op, patient()));
        var changed = new HashMap<>(patient());
        changed.put("nombres", "Different");
        var before = OfflineSyncHttpSupport.snapshot(port);
        JsonNode conflict = sync(session, input(session, op, changed));
        assertThat(conflict.path("state").asText()).isEqualTo("CONFLICT");
        assertThat(conflict.path("reasonCode").asText()).isEqualTo("PAYLOAD_MISMATCH");
        assertThat(conflict.path("payloadHash").asText()).isEqualTo(original.path("payloadHash").asText());
        assertThat(conflict.path("result").isNull()).isTrue();
        assertThat(status(session, op)).isEqualTo(original);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void aNewLoginAfterFortyEightHoursDoesNotExpireOrDuplicateTheIntent() throws Exception {
        var session = session("ADMIN", true);
        UUID op = UUID.randomUUID();
        JsonNode original = sync(session, input(session, op, patient()));
        setTime(session.auth().plus(48, ChronoUnit.HOURS));
        var renewed = renew(session);
        var before = OfflineSyncHttpSupport.snapshot(port);
        assertThat(sync(renewed, input(renewed, op, patient()))).isEqualTo(original);
        assertThat(status(renewed, op)).isEqualTo(original);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void operationTypeCannotReplaceAnExistingActorOperationIdentity() throws Exception {
        var session = session("ADMIN", true);
        UUID op = UUID.randomUUID();
        JsonNode original = sync(session, input(session, op, patient()));
        var before = OfflineSyncHttpSupport.snapshot(port);
        JsonNode conflict = sync(session, Map.of("sessionId", session.id().toString(),
                "operationId", op.toString(), "type", "CASH_EXPENSE", "expense", expense()));
        assertThat(conflict.path("state").asText()).isEqualTo("CONFLICT");
        assertThat(conflict.path("reasonCode").asText()).isEqualTo("OPERATION_TYPE_MISMATCH");
        assertThat(status(session, op)).isEqualTo(original);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "RECEPCIONISTA"})
    void patientIntentUsesTheCurrentAllowedRole(String role) throws Exception {
        var session = session(role, true);
        var before = OfflineSyncHttpSupport.snapshot(port);
        assertThat(sync(session, input(session, UUID.randomUUID(), patient())).path("state").asText())
                .isEqualTo("PREPARED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void cashierCannotClaimOrReadARevokedPatientIntent() throws Exception {
        var cashier = session("CAJERO", true);
        UUID absent = UUID.randomUUID();
        var before = OfflineSyncHttpSupport.snapshot(port);
        var denied = OfflineSyncHttpSupport.send(port, SYNC, Map.of("input", input(cashier, absent, patient())),
                cashier.token(), null, null);
        OfflineSyncHttpSupport.error(denied, "PERMISSION_REVOKED");
        assertThat(status(cashier, absent).path("state").asText()).isEqualTo("NOT_FOUND");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
        var admin = session("ADMIN", true);
        UUID op = UUID.randomUUID();
        JsonNode original = sync(admin, input(admin, op, patient()));
        changeUser(admin, "CAJERO", true);
        before = OfflineSyncHttpSupport.snapshot(port);
        String currentRoleToken = OfflineSyncHttpSupport.token(admin.actor(), "CAJERO",
                admin.auth().getEpochSecond(), java.time.Instant.now(), admin.expiry());
        denied = OfflineSyncHttpSupport.send(port, STATUS, Map.of("session", admin.id().toString(),
                "op", op.toString()), currentRoleToken, null, null);
        OfflineSyncHttpSupport.error(denied, "PERMISSION_REVOKED");
        assertThat(denied.body()).doesNotContain(original.path("payloadHash").asText());
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void multiplePayloadsAreRejectedWithoutClaimingAnIntent() throws Exception {
        var session = session("ADMIN", true);
        UUID op = UUID.randomUUID();
        var body = new HashMap<>(input(session, op, patient()));
        body.put("expense", expense());
        var before = OfflineSyncHttpSupport.snapshot(port);
        var denied = OfflineSyncHttpSupport.send(port, SYNC, Map.of("input", body), session.token(), null, null);
        OfflineSyncHttpSupport.error(denied, "INVALID_INPUT");
        assertThat(status(session, op).path("state").asText()).isEqualTo("NOT_FOUND");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    static Map<String, Object> expense() {
        return Map.of("categoria", "INSUMOS", "metodoPago", "EFECTIVO", "montoBs", "1.00",
                "descripcion", "Synthetic", "expectedCashflowBalanceBs", "0");
    }
}
