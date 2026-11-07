package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.configsecurity.application.port.OfflineSyncIdentityPort;
import com.dentalplatform.erp.offline.application.ConfirmOfflineSyncUseCase;
import com.dentalplatform.erp.offline.application.ExecuteOfflineOperationUseCase;
import com.dentalplatform.erp.offline.application.OfflineOperationRequest;
import com.dentalplatform.erp.offline.application.OfflineOperationStore;
import com.dentalplatform.erp.offline.application.OfflinePayloadCodec;
import com.dentalplatform.erp.offline.application.ReserveOfflineOperationUseCase;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/** A denial must leave an immutable, durable record that still refuses altered intent. */
class OfflineSyncDenialHttpTest extends OfflineOperationHttpTestSupport {
    @MockitoSpyBean ExecuteOfflineOperationUseCase execution;
    @MockitoSpyBean ReserveOfflineOperationUseCase reservation;
    // Real collaborators, so a use case built inside a test runs the production lock, ledger and clock paths.
    @Autowired ConfirmOfflineSyncUseCase sessions;
    @Autowired OfflineOperationStore operations;
    @Autowired OfflineSyncIdentityPort identities;
    @Autowired OfflinePayloadCodec codec;
    @Autowired PlatformTransactionManager transactionManager;
    private static final String FIELDS = "operationId type state payloadHash reasonCode result { patientId }";
    private static final String SYNC = "mutation($input:OfflineOperationInput!){sincronizarOperacionOffline(input:$input){"
            + FIELDS + "}}";
    private static final String STATUS = "query($session:UUID!,$operationId:UUID!){estadoOperacionOffline(sessionId:$session,operationId:$operationId){"
            + FIELDS + "}}";

    private Map<String, Object> input(Session session, UUID operationId, String ciNit) {
        return Map.of("sessionId", session.id().toString(), "operationId", operationId.toString(),
                "type", "PATIENT_CREATE", "patient", patient(ciNit));
    }

    /** The exact frozen patient payload the sync input carries, so a direct reservation hashes the same bytes. */
    private Map<String, Object> patient(String ciNit) {
        return Map.of("ciNit", ciNit, "nombres", "Denied", "apellidos", "Patient",
                "telefonoWhatsapp", "+59170012345");
    }

    private JsonNode sync(Session session, Map<String, Object> input) throws Exception {
        return OfflineSyncHttpSupport.success(port, SYNC, Map.of("input", input), session.token())
                .path("sincronizarOperacionOffline");
    }

    private JsonNode send(Session session, Map<String, Object> input) throws Exception {
        return OfflineSyncHttpSupport.success(port, SYNC, Map.of("input", input), session.token())
                .path("sincronizarOperacionOffline");
    }

    private JsonNode status(Session session, UUID operationId) throws Exception {
        return OfflineSyncHttpSupport.success(port, STATUS, Map.of("session", session.id().toString(),
                "operationId", operationId.toString()), session.token()).path("estadoOperacionOffline");
    }

    /**
     * Runs a direct use-case call under the same validated identity an HTTP request would carry, so the call
     * reaches the ledger instead of silently no-opping on an unavailable actor.
     */
    private void asCurrentActor(Session session, Runnable call) throws Exception {
        var claims = SignedJWT.parse(session.token()).getJWTClaimsSet().getClaims();
        // Spring Security 7 requires Instant timestamps in the Jwt builder, not java.util.Date from Nimbus.
        var normalized = new java.util.HashMap<String, Object>(claims);
        for (String time : new String[]{"iat", "exp", "nbf", "auth_time"}) {
            if (normalized.get(time) instanceof java.util.Date date) normalized.put(time, date.toInstant());
        }
        Jwt jwt = Jwt.withTokenValue(session.token()).header("alg", "RS256")
                .claims(values -> values.putAll(normalized)).build();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(jwt,
                List.of(new SimpleGrantedAuthority("ROLE_" + session.role())), session.actor().username()));
        SecurityContextHolder.setContext(context);
        try {
            call.run();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void recordDeniedAfterConfirmationIsANoOpThatPreservesTheConfirmedReceipt() throws Exception {
        var session = session("ADMIN", true);
        UUID operationId = UUID.randomUUID();
        String ciNit = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        var request = new OfflineOperationRequest(session.id(), operationId,
                OfflineOperationReceipt.Type.PATIENT_CREATE, null, null, null, Map.of("ciNit", ciNit,
                "nombres", "Confirmed", "apellidos", "Patient", "telefonoWhatsapp", "+59170012345"));
        JsonNode confirmed = sync(session, input(session, operationId, ciNit));
        assertThat(confirmed.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("result").path("patientId").asText()).isNotBlank();
        assertThat(confirmed.path("reasonCode").isNull()).isTrue();
        var before = OfflineSyncHttpSupport.snapshot(port);
        // This is a regression guard: the ledger only records a denial while state <> 'CONFIRMED', so a denial
        // arriving after the confirmation commits must leave state, result and business data untouched.
        asCurrentActor(session, () -> execution.recordDenied(request, new OfflineSyncFailure("SESSION_EXPIRED")));
        assertThat(status(session, operationId)).isEqualTo(confirmed);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void anotherCallerCannotBlockTheOwnersIntentByPresentingItsSessionAndOperation() throws Exception {
        var owner = session("ADMIN", true);
        var intruder = session("ADMIN", true);
        UUID operationId = UUID.randomUUID();
        String ciNit = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        var request = new OfflineOperationRequest(owner.id(), operationId,
                OfflineOperationReceipt.Type.PATIENT_CREATE, null, null, null, patient(ciNit));
        // The owner commits its own PREPARED reservation through the same validated identity an HTTP call carries.
        asCurrentActor(owner, () -> reservation.reserve(request));
        assertThat(status(owner, operationId).path("state").asText()).isEqualTo("PREPARED");
        var before = OfflineSyncHttpSupport.snapshot(port);
        var beforeLedger = OfflineSyncHttpSupport.offlineLedger();

        // A different authenticated caller presenting the owner's sessionId and operationId is rejected by the
        // session/actor binding before any business work happens.
        var denied = OfflineSyncHttpSupport.send(port, SYNC, Map.of("input", input(owner, operationId, ciNit)),
                intruder.token(), null, null);
        OfflineSyncHttpSupport.error(denied, "SESSION_ACTOR_MISMATCH");

        // Recording that denial must never reach the owner's row: no other caller, not even with a leaked
        // sessionId, may move another actor's intent to BLOCKED or overwrite its reason.
        var untouched = status(owner, operationId);
        assertThat(untouched.path("state").asText()).isEqualTo("PREPARED");
        assertThat(untouched.path("reasonCode").isNull()).isTrue();
        assertThat(untouched.path("result").isNull()).isTrue();
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
        // The ledger table itself is unchanged, so no denial row was written for the owner's intent.
        assertThat(OfflineSyncHttpSupport.offlineLedger()).isEqualTo(beforeLedger);

        // Control: the owner's identical frozen intent was not poisoned and still runs exactly once.
        var confirmed = sync(owner, input(owner, operationId, ciNit));
        assertThat(confirmed.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("result").path("patientId").asText()).isNotBlank();
    }

    @Test
    void unsupportedOperationTypeIsATypedErrorAndNeverAPreparedReceipt() throws Exception {
        var session = session("ADMIN", true);
        UUID operationId = UUID.randomUUID();
        var operation = input(session, operationId, UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        // A missing handler must never be reported as a successful preparation the client keeps pending.
        doReturn(false).when(execution).supports(any());
        var before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, SYNC, Map.of("input", operation), session.token(), null, null);
        OfflineSyncHttpSupport.error(response, "INVALID_INPUT");
        var data = OfflineSyncHttpSupport.json(response.body()).path("data");
        assertThat(data.isNull() || data.path("sincronizarOperacionOffline").isNull()).isTrue();
        assertThat(response.body()).doesNotContain("PREPARED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void reservationDenialOnAnExistingPreparedIntentIsRecordedAsBlocked() throws Exception {
        var session = session("ADMIN", true);
        UUID operationId = UUID.randomUUID();
        String ciNit = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        var request = new OfflineOperationRequest(session.id(), operationId,
                OfflineOperationReceipt.Type.PATIENT_CREATE, null, null, null,
                Map.of("ciNit", ciNit, "nombres", "Denied", "apellidos", "Patient",
                        "telefonoWhatsapp", "+59170012345"));
        // Commit a real PREPARED reservation through the same validated identity the HTTP path uses.
        asCurrentActor(session, () -> reservation.reserve(request));
        assertThat(status(session, operationId).path("state").asText()).isEqualTo("PREPARED");
        // The actor no longer holds the permission the existing intent needs, so the reservation gate denies it.
        changeUser(session, "CAJERO", true);
        // Baseline AFTER the role change: the denial must not add business effects on top of it.
        var before = OfflineSyncHttpSupport.snapshot(port);
        var denied = OfflineSyncHttpSupport.send(port, SYNC, Map.of("input", input(session, operationId, ciNit)),
                session.token(), null, null);
        var denialCode = OfflineSyncHttpSupport.json(denied.body()).path("errors").get(0)
                .path("extensions").path("code").asText();
        assertThat(denialCode).isNotBlank();
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);

        // Reading the intent needs the permission it belongs to, so restore the role before inspecting it:
        // the denial itself was already recorded while the actor could not execute it.
        changeUser(session, "ADMIN", true);
        var blocked = status(session, operationId);
        assertThat(blocked.path("state").asText()).isEqualTo("BLOCKED");
        assertThat(blocked.path("reasonCode").asText()).isEqualTo(denialCode);
        assertThat(blocked.path("result").isNull()).isTrue();
        assertThat(blocked.path("payloadHash").asText()).matches("[0-9a-f]{64}");

        // With the permission restored, the identical frozen intent still runs exactly once.
        var confirmed = sync(session, input(session, operationId, ciNit));
        assertThat(confirmed.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("payloadHash").asText()).isEqualTo(blocked.path("payloadHash").asText());
        assertThat(confirmed.path("result").path("patientId").asText()).isNotBlank();
        assertThat(status(session, operationId).path("state").asText()).isEqualTo("CONFIRMED");
        var after = OfflineSyncHttpSupport.snapshot(port);
        assertThat(after.path("listarPacientes").size()).isEqualTo(before.path("listarPacientes").size() + 1);
    }

    @Test
    void aTerminalConflictKeepsItsDurableBusinessReasonWhenADenialArrivesLater() throws Exception {
        var session = session("ADMIN", true);
        String ciNit = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        // The first operation registers the patient, so the second one hits the real duplicate-CI business rule.
        UUID confirmedOperation = UUID.randomUUID();
        assertThat(sync(session, input(session, confirmedOperation, ciNit)).path("state").asText())
                .isEqualTo("CONFIRMED");
        UUID conflictedOperation = UUID.randomUUID();
        var conflict = sync(session, input(session, conflictedOperation, ciNit));
        assertThat(conflict.path("state").asText()).isEqualTo("CONFLICT");
        assertThat(conflict.path("reasonCode").asText()).isEqualTo("PATIENT_DUPLICATE");
        assertThat(conflict.path("result").isNull()).isTrue();

        var request = new OfflineOperationRequest(session.id(), conflictedOperation,
                OfflineOperationReceipt.Type.PATIENT_CREATE, null, null, null, patient(ciNit));
        var before = OfflineSyncHttpSupport.snapshot(port);
        // CONFLICT is terminal for explicit review: a later denial for the same session/operation must not
        // replace the durable business reason the operator has to resolve, and it must not resurrect a result.
        asCurrentActor(session, () -> execution.recordDenied(request, new OfflineSyncFailure("PERMISSION_REVOKED")));
        var unchanged = status(session, conflictedOperation);
        assertThat(unchanged).isEqualTo(conflict);
        assertThat(unchanged.path("state").asText()).isEqualTo("CONFLICT");
        assertThat(unchanged.path("reasonCode").asText()).isEqualTo("PATIENT_DUPLICATE");
        assertThat(unchanged.path("result").isNull()).isTrue();
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    /**
     * FIX 3 decision: a reservation conflict is a durable classification of the intent that already exists, not a
     * transient response. Today the row stays PREPARED with a null reason, so the status endpoint hides the
     * conflict the operator has to resolve, and a client that only reads status retries a payment blind.
     */
    @Test
    void aReservationConflictIsRecordedOnTheExistingIntentAndStillLeavesItExecutable() throws Exception {
        var session = session("ADMIN", true);
        UUID operationId = UUID.randomUUID();
        String frozenCi = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String alteredCi = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        var request = new OfflineOperationRequest(session.id(), operationId,
                OfflineOperationReceipt.Type.PATIENT_CREATE, null, null, null, patient(frozenCi));
        // Reserved and deliberately NOT confirmed: an interrupted sync leaves exactly this bare PREPARED row,
        // which is the state a conflict must never leave behind without its reason code.
        asCurrentActor(session, () -> reservation.reserve(request));
        assertThat(status(session, operationId).path("state").asText()).isEqualTo("PREPARED");
        assertThat(status(session, operationId).path("reasonCode").isNull()).isTrue();
        var before = OfflineSyncHttpSupport.snapshot(port);
        var beforeLedger = OfflineSyncHttpSupport.offlineLedger();

        // The client resends the SAME operationId with a different frozen payload.
        var conflict = send(session, input(session, operationId, alteredCi));
        assertThat(conflict.path("state").asText()).isEqualTo("CONFLICT");
        assertThat(conflict.path("reasonCode").asText()).isEqualTo("PAYLOAD_MISMATCH");
        assertThat(conflict.path("result").isNull()).isTrue();

        // The conflict is recorded on the existing row with the same PREPARED|BLOCKED guard a denial uses, so the
        // status endpoint never reports a bare PREPARED with a null reason.
        var recorded = status(session, operationId);
        assertThat(recorded.path("state").asText()).isEqualTo("BLOCKED");
        assertThat(recorded.path("reasonCode").asText()).isEqualTo("PAYLOAD_MISMATCH");
        assertThat(recorded.path("type").asText()).isEqualTo("PATIENT_CREATE");
        // The frozen intent is untouched: only state and reason moved.
        assertThat(recorded.path("payloadHash").asText()).isEqualTo(conflict.path("payloadHash").asText());
        assertThat(recorded.path("result").isNull()).isTrue();
        var ledger = OfflineSyncHttpSupport.offlineLedger();
        assertThat(ledger.size()).isEqualTo(beforeLedger.size());
        JsonNode frozenRow = null;
        for (JsonNode row : ledger) if (row.path("operationId").asText().equals(operationId.toString())) frozenRow = row;
        assertThat(frozenRow).as("the conflict must be recorded on the existing reservation").isNotNull();
        assertThat(frozenRow.path("type").asText()).isEqualTo("PATIENT_CREATE");
        assertThat(frozenRow.path("state").asText()).isEqualTo("BLOCKED");
        assertThat(frozenRow.path("reasonCode").asText()).isEqualTo("PAYLOAD_MISMATCH");
        // No business effect was written for the rejected intent.
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);

        // BLOCKED is still executable, so the original frozen intent confirms exactly once.
        var confirmed = sync(session, input(session, operationId, frozenCi));
        assertThat(confirmed.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("payloadHash").asText()).isEqualTo(conflict.path("payloadHash").asText());
        assertThat(confirmed.path("result").path("patientId").asText()).matches("[0-9a-f-]{36}");
        assertThat(OfflineSyncHttpSupport.snapshot(port).path("listarPacientes").size())
                .isEqualTo(before.path("listarPacientes").size() + 1);
        // Lost acknowledgement: the same frozen intent answers with the same confirmation and no second patient.
        assertThat(sync(session, input(session, operationId, frozenCi))).isEqualTo(confirmed);
        assertThat(OfflineSyncHttpSupport.snapshot(port).path("listarPacientes").size())
                .isEqualTo(before.path("listarPacientes").size() + 1);
        // A denial arriving after the confirmation cannot rewrite the confirmed receipt.
        asCurrentActor(session, () -> execution.recordDenied(request, new OfflineSyncFailure("SESSION_EXPIRED")));
        assertThat(status(session, operationId)).isEqualTo(confirmed);
    }

    /**
     * FIX 4(b) guard: the missing-handler invariant also lives in execute(). The reachable facade check cannot
     * prove it, so the use case is built here without any handler and asked to execute a real frozen reservation.
     */
    @Test
    void executeRejectsAFrozenTypeWithoutAHandlerInsteadOfReturningPrepared() throws Exception {
        var session = session("ADMIN", true);
        UUID operationId = UUID.randomUUID();
        String ciNit = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        var request = new OfflineOperationRequest(session.id(), operationId,
                OfflineOperationReceipt.Type.PATIENT_CREATE, null, null, null, patient(ciNit));
        asCurrentActor(session, () -> reservation.reserve(request));
        assertThat(status(session, operationId).path("state").asText()).isEqualTo("PREPARED");
        var withoutHandlers = new ExecuteOfflineOperationUseCase(sessions, operations, identities, codec, clock,
                transactionManager, List.of());
        var transactions = new TransactionTemplate(transactionManager);
        var thrown = new AtomicReference<Throwable>();
        // Running the un-proxied instance inside a real transaction keeps the production row locks in play, so the
        // call reaches the handler lookup instead of failing earlier for lack of a transaction.
        asCurrentActor(session, () -> {
            try {
                transactions.execute(status -> withoutHandlers.execute(request));
            } catch (RuntimeException failure) {
                thrown.set(failure);
            }
        });
        assertThat(thrown.get()).isInstanceOf(OfflineSyncFailure.class);
        assertThat(((OfflineSyncFailure) thrown.get()).code()).isEqualTo("INVALID_INPUT");
        // The rejected execution wrote nothing: the frozen reservation is still the untouched PREPARED intent.
        var untouched = status(session, operationId);
        assertThat(untouched.path("state").asText()).isEqualTo("PREPARED");
        assertThat(untouched.path("reasonCode").isNull()).isTrue();
        assertThat(untouched.path("result").isNull()).isTrue();
    }

    @Test
    void deniedIntentStaysImmutableAndRefusesAlteredPayload() throws Exception {
        var session = session("ADMIN", true);
        UUID operationId = UUID.randomUUID();
        String originalCi = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        String alteredCi = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        var denyOnce = new AtomicBoolean(true);
        // Position a denial after the intent reservation, exactly where the live gate runs in a real request.
        doAnswer(call -> {
            if (denyOnce.getAndSet(false)) throw new OfflineSyncFailure("SESSION_EXPIRED");
            return call.callRealMethod();
        }).when(execution).execute(any());
        var before = OfflineSyncHttpSupport.snapshot(port);
        var denied = OfflineSyncHttpSupport.send(port, SYNC, Map.of("input", input(session, operationId, originalCi)),
                session.token(), null, null);
        OfflineSyncHttpSupport.error(denied, "SESSION_EXPIRED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);

        // The denial is durable but is not a pending operation for the client.
        var afterDenial = status(session, operationId);
        assertThat(afterDenial.path("state").asText()).isEqualTo("BLOCKED");
        assertThat(afterDenial.path("reasonCode").asText()).isEqualTo("SESSION_EXPIRED");
        assertThat(afterDenial.path("result").isNull()).isTrue();

        // Altered intent with the same operationId still conflicts: the reservation was never erased.
        var altered = send(session, input(session, operationId, alteredCi));
        assertThat(altered.path("state").asText()).isEqualTo("CONFLICT");
        assertThat(altered.path("reasonCode").asText()).isEqualTo("PAYLOAD_MISMATCH");
        assertThat(altered.path("result").isNull()).isTrue();
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);

        // The original frozen intent still runs exactly once once the request is allowed again.
        var confirmed = sync(session, input(session, operationId, originalCi));
        assertThat(confirmed.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(confirmed.path("result").path("patientId").asText()).isNotBlank();
        assertThat(status(session, operationId).path("state").asText()).isEqualTo("CONFIRMED");
        var after = OfflineSyncHttpSupport.snapshot(port);
        assertThat(after.path("listarPacientes").size()).isEqualTo(before.path("listarPacientes").size() + 1);
    }
}
