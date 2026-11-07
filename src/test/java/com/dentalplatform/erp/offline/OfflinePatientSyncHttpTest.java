package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.mastercatalog.application.usecase.CrearPacienteUseCase;
import com.dentalplatform.erp.offline.application.OfflineOperationStore;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class OfflinePatientSyncHttpTest extends OfflineOperationHttpTestSupport {
    @MockitoSpyBean OfflineOperationStore operations;
    @MockitoSpyBean CrearPacienteUseCase patients;
    private static final String SYNC = OfflineOperationIntentHttpTest.SYNC;
    private static final String STATUS = OfflineOperationIntentHttpTest.STATUS;

    private Map<String, Object> patient() {
        return Map.of("ciNit", UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                "nombres", "Offline", "apellidos", "Synthetic", "telefonoWhatsapp", "+59170012345",
                "fechaNacimiento", "2000-01-02");
    }

    private Map<String, Object> input(Session session, UUID op, Map<String, Object> payload) {
        return Map.of("sessionId", session.id().toString(), "operationId", op.toString(),
                "type", "PATIENT_CREATE", "patient", payload);
    }

    private JsonNode sync(Session session, UUID op, Map<String, Object> payload) throws Exception {
        return OfflineSyncHttpSupport.success(port, SYNC, Map.of("input", input(session, op, payload)),
                session.token()).path("sincronizarOperacionOffline");
    }

    private JsonNode status(Session session, UUID op) throws Exception {
        return OfflineSyncHttpSupport.success(port, STATUS, Map.of("session", session.id().toString(),
                "op", op.toString()), session.token()).path("estadoOperacionOffline");
    }

    private void confirmed(JsonNode receipt, UUID op) {
        assertThat(receipt.path("operationId").asText()).isEqualTo(op.toString());
        assertThat(receipt.path("type").asText()).isEqualTo("PATIENT_CREATE");
        assertThat(receipt.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(receipt.path("payloadHash").asText()).matches("[0-9a-f]{64}");
        assertThat(receipt.path("reasonCode").isNull()).isTrue();
        assertThat(receipt.path("confirmedAt").asText()).isNotBlank();
        assertThat(receipt.path("result").path("patientId").asText()).matches("[0-9a-f-]{36}");
    }

    private void mappedPatient(JsonNode receipt, Map<String, Object> payload) throws Exception {
        var patients = OfflineSyncHttpSupport.success(port, """
                { listarPacientes { id ciNit nombres apellidos fechaNacimiento genero telefonoWhatsapp email direccion activo } }
                """, Map.of(), OfflineSyncHttpSupport.admin()).path("listarPacientes");
        JsonNode actual = null;
        for (JsonNode patient : patients) {
            if (patient.path("id").asText().equals(receipt.path("result").path("patientId").asText())) actual = patient;
        }
        assertThat(actual).as("confirmed mapping must point to the stored patient").isNotNull();
        for (String field : new String[]{"ciNit", "nombres", "apellidos", "fechaNacimiento", "telefonoWhatsapp"}) {
            assertThat(actual.path(field).asText()).as(field).isEqualTo(payload.get(field).toString());
        }
        for (String field : new String[]{"genero", "email", "direccion"}) assertThat(actual.path(field).isNull()).as(field).isTrue();
        assertThat(actual.path("activo").asBoolean()).isTrue();
    }

    private long patientAudits(JsonNode snapshot) {
        long count = 0;
        for (JsonNode audit : snapshot.path("listarAuditLogs")) {
            if (audit.path("accion").asText().equals("PACIENTE_CREADO")) count++;
        }
        return count;
    }

    private void onePatient(JsonNode before) throws Exception {
        var after = OfflineSyncHttpSupport.snapshot(port);
        assertThat(after.path("listarPacientes").size()).isEqualTo(before.path("listarPacientes").size() + 1);
        assertThat(patientAudits(after)).isEqualTo(patientAudits(before) + 1);
        assertThat(after.path("listarOrdenesCobro")).isEqualTo(before.path("listarOrdenesCobro"));
        assertThat(after.path("obtenerFlujoCaja")).isEqualTo(before.path("obtenerFlujoCaja"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "RECEPCIONISTA"})
    void confirmsExactlyOnePatientAndReturnsTheSameReceiptAfterLostAcknowledgement(String role) throws Exception {
        var session = session(role, true);
        var payload = patient();
        UUID op = UUID.randomUUID();
        var before = OfflineSyncHttpSupport.snapshot(port);
        JsonNode first = sync(session, op, payload);
        confirmed(first, op);
        mappedPatient(first, payload);
        onePatient(before);
        var afterFirst = OfflineSyncHttpSupport.snapshot(port);
        assertThat(sync(session, op, payload)).isEqualTo(first);
        assertThat(status(session, op)).isEqualTo(first);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(afterFirst);
    }

    @Test
    void newLoginAfterMockedFortyEightHoursReplaysConfirmedResultWithoutAnotherPatient() throws Exception {
        var session = session("ADMIN", true);
        var payload = patient();
        UUID op = UUID.randomUUID();
        JsonNode original = sync(session, op, payload);
        confirmed(original, op);
        setTime(session.auth().plusSeconds(48 * 3600));
        var renewed = renew(session);
        var before = OfflineSyncHttpSupport.snapshot(port);
        assertThat(sync(renewed, op, payload)).isEqualTo(original);
        assertThat(status(renewed, op)).isEqualTo(original);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void alteredFrozenPayloadCannotOverwriteAConfirmedOutcome() throws Exception {
        var session = session("ADMIN", true);
        UUID op = UUID.randomUUID();
        var payload = patient();
        JsonNode original = sync(session, op, payload);
        confirmed(original, op);
        var changed = new java.util.HashMap<>(payload);
        changed.put("nombres", "Changed");
        var before = OfflineSyncHttpSupport.snapshot(port);
        JsonNode conflict = sync(session, op, changed);
        assertThat(conflict.path("state").asText()).isEqualTo("CONFLICT");
        assertThat(conflict.path("reasonCode").asText()).isEqualTo("PAYLOAD_MISMATCH");
        assertThat(conflict.path("result").isNull()).isTrue();
        assertThat(status(session, op)).isEqualTo(original);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void anotherActorCannotReadTheReceiptAndHasASeparateOperationNamespace() throws Exception {
        var first = session("ADMIN", true);
        var second = session("ADMIN", true);
        UUID op = UUID.randomUUID();
        JsonNode original = sync(first, op, patient());
        confirmed(original, op);
        assertThat(status(second, op).path("state").asText()).isEqualTo("NOT_FOUND");
        JsonNode other = sync(second, op, patient());
        confirmed(other, op);
        assertThat(other.path("result").path("patientId").asText())
                .isNotEqualTo(original.path("result").path("patientId").asText());
        assertThat(status(first, op)).isEqualTo(original);
    }

    @Test
    void duplicateCiIsAConflictWithNoSecondPatientOrAudit() throws Exception {
        var session = session("ADMIN", true);
        var payload = patient();
        UUID first = UUID.randomUUID();
        confirmed(sync(session, first, payload), first);
        UUID duplicate = UUID.randomUUID();
        var before = OfflineSyncHttpSupport.snapshot(port);
        JsonNode conflict = sync(session, duplicate, payload);
        assertThat(conflict.path("state").asText()).isEqualTo("CONFLICT");
        assertThat(conflict.path("reasonCode").asText()).isEqualTo("PATIENT_DUPLICATE");
        assertThat(conflict.path("result").isNull()).isTrue();
        assertThat(status(session, duplicate)).isEqualTo(conflict);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(strings = {"role", "inactive"})
    void revokedLivePermissionBlocksConfirmedStatusAndReplayWithoutDisclosure(String change) throws Exception {
        var session = session("ADMIN", true);
        UUID op = UUID.randomUUID();
        var payload = patient();
        JsonNode original = sync(session, op, payload);
        confirmed(original, op);
        changeUser(session, change.equals("role") ? "CAJERO" : "ADMIN", !change.equals("inactive"));
        var before = OfflineSyncHttpSupport.snapshot(port);
        String code = change.equals("role") ? "PERMISSION_REVOKED" : "ACTOR_UNAVAILABLE";
        for (String query : List.of(SYNC, STATUS)) {
            Map<String, Object> args = query.equals(SYNC) ? Map.of("input", input(session, op, payload))
                    : Map.of("session", session.id().toString(), "op", op.toString());
            var response = OfflineSyncHttpSupport.send(port, query, args, session.token(), null, null);
            OfflineSyncHttpSupport.error(response, code);
            assertThat(response.body()).doesNotContain(original.path("result").path("patientId").asText());
        }
        if (change.equals("role")) {
            String token = OfflineSyncHttpSupport.token(session.actor(), "CAJERO", session.auth().getEpochSecond(),
                    java.time.Instant.now(), session.expiry());
            var response = OfflineSyncHttpSupport.send(port, STATUS, Map.of("session", session.id().toString(),
                    "op", op.toString()), token, null, null);
            OfflineSyncHttpSupport.error(response, "PERMISSION_REVOKED");
            assertThat(response.body()).doesNotContain(original.path("payloadHash").asText());
        }
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    private List<JsonNode> concurrently(Session first, Session second, UUID a, UUID b,
                                        Map<String, Object> payload) throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var ready = new CountDownLatch(2);
            var go = new CountDownLatch(1);
            var left = executor.submit(() -> { ready.countDown(); if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout"); return sync(first, a, payload); });
            var right = executor.submit(() -> { ready.countDown(); if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout"); return sync(second, b, payload); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            return List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
        }
    }

    @Test
    void concurrentSessionsWithOneOperationUuidCreateOneCanonicalPatient() throws Exception {
        var first = session("ADMIN", true);
        var second = renew(first);
        UUID op = UUID.randomUUID();
        var before = OfflineSyncHttpSupport.snapshot(port);
        var outcomes = concurrently(first, second, op, op, patient());
        confirmed(outcomes.get(0), op);
        assertThat(outcomes.get(1)).isEqualTo(outcomes.get(0));
        onePatient(before);
    }

    @Test
    void concurrentDifferentOperationsForOneCiHaveOneWinnerAndOneDuplicateConflict() throws Exception {
        var first = session("ADMIN", true);
        var second = renew(first);
        var payload = patient();
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var before = OfflineSyncHttpSupport.snapshot(port);
        var inside = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        // Both requests pass the CI/NIT precheck together, so PostgreSQL's unique index decides the race
        // instead of the precheck; the loser must keep the domain classification, never a technical retry.
        doAnswer(call -> {
            inside.countDown();
            if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout");
            return call.callRealMethod();
        }).when(patients).ejecutar(any());
        List<JsonNode> outcomes;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> sync(first, a, payload));
            var right = executor.submit(() -> sync(second, b, payload));
            try {
                assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                go.countDown();
            }
            outcomes = List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
        }
        assertThat(outcomes.stream().map(result -> result.path("state").asText()).toList())
                .containsExactlyInAnyOrder("CONFIRMED", "CONFLICT");
        JsonNode winner = outcomes.stream().filter(result -> result.path("state").asText().equals("CONFIRMED"))
                .findFirst().orElseThrow();
        JsonNode rejected = outcomes.stream().filter(result -> result.path("state").asText().equals("CONFLICT"))
                .findFirst().orElseThrow();
        assertThat(rejected.path("reasonCode").asText()).isEqualTo("PATIENT_DUPLICATE");
        assertThat(rejected.path("result").isNull()).isTrue();
        assertThat(status(first, UUID.fromString(rejected.path("operationId").asText()))).isEqualTo(rejected);
        onePatient(before);
        // The losing attempt leaves no second row: exactly one patient exists for that CI/NIT.
        int stored = 0;
        for (JsonNode patient : OfflineSyncHttpSupport.success(port, "{ listarPacientes { id ciNit } }",
                Map.of(), OfflineSyncHttpSupport.admin()).path("listarPacientes")) {
            if (patient.path("ciNit").asText().equals(payload.get("ciNit"))) {
                stored++;
                assertThat(patient.path("id").asText()).isEqualTo(winner.path("result").path("patientId").asText());
            }
        }
        assertThat(stored).isEqualTo(1);
    }

    @Test
    void malformedPatientValueIsATypedConflictInsteadOfARetryablePreparedIntent() throws Exception {
        var session = session("ADMIN", true);
        UUID op = UUID.randomUUID();
        // The canonical codec passes nombres through as TEXT, so this reaches the patient handler; PostgreSQL
        // rejects the NUL character for a text column, and that data-level rejection must be classified as an
        // explicit INVALID_INPUT conflict instead of a retryable PREPARED intent.
        var payload = new java.util.HashMap<>(patient());
        payload.put("nombres", "Off\u0000line");
        var before = OfflineSyncHttpSupport.snapshot(port);
        JsonNode rejected = sync(session, op, payload);
        assertThat(rejected.path("state").asText()).isEqualTo("CONFLICT");
        assertThat(rejected.path("reasonCode").asText()).isEqualTo("INVALID_INPUT");
        assertThat(rejected.path("result").isNull()).isTrue();
        assertThat(status(session, op)).isEqualTo(rejected);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void failureAfterJournalConfirmationRollsBackPatientAuditAndConfirmationButKeepsFrozenIntent() throws Exception {
        var session = session("ADMIN", true);
        var payload = patient();
        UUID op = UUID.randomUUID();
        var failOnce = new AtomicBoolean(true);
        doAnswer(call -> {
            @SuppressWarnings("unchecked")
            Optional<OfflineOperationReceipt> result = (Optional<OfflineOperationReceipt>) call.callRealMethod();
            if (result.isPresent() && result.get().state() == OfflineOperationReceipt.State.CONFIRMED
                    && failOnce.getAndSet(false)) {
                throw new TransientDataAccessResourceException("Synthetic failure after confirmation SQL");
            }
            return result;
        }).when(operations).receipt(any(UUID.class), any(UUID.class));
        var before = OfflineSyncHttpSupport.snapshot(port);
        JsonNode failed = sync(session, op, payload);
        assertThat(failed.path("state").asText()).isEqualTo("PREPARED");
        assertThat(failed.path("reasonCode").asText()).isEqualTo("TECHNICAL_RETRY_REQUIRED");
        assertThat(failed.path("result").isNull()).isTrue();
        assertThat(status(session, op)).isEqualTo(failed);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
        JsonNode recovered = sync(session, op, payload);
        confirmed(recovered, op);
        assertThat(recovered.path("payloadHash").asText()).isEqualTo(failed.path("payloadHash").asText());
        onePatient(before);
    }
}
