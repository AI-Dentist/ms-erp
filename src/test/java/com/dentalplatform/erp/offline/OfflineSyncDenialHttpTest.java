package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.offline.application.ExecuteOfflineOperationUseCase;
import com.dentalplatform.erp.offline.application.OfflineOperationRequest;
import com.dentalplatform.erp.offline.application.ReserveOfflineOperationUseCase;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

/** A denial must leave an immutable, durable record that still refuses altered intent. */
class OfflineSyncDenialHttpTest extends OfflineOperationHttpTestSupport {
    @MockitoSpyBean ExecuteOfflineOperationUseCase execution;
    @MockitoSpyBean ReserveOfflineOperationUseCase reservation;
    private static final String FIELDS = "operationId type state payloadHash reasonCode result { patientId }";
    private static final String SYNC = "mutation($input:OfflineOperationInput!){sincronizarOperacionOffline(input:$input){"
            + FIELDS + "}}";
    private static final String STATUS = "query($session:UUID!,$operationId:UUID!){estadoOperacionOffline(sessionId:$session,operationId:$operationId){"
            + FIELDS + "}}";

    private Map<String, Object> input(Session session, UUID operationId, String ciNit) {
        return Map.of("sessionId", session.id().toString(), "operationId", operationId.toString(),
                "type", "PATIENT_CREATE", "patient", Map.of("ciNit", ciNit,
                        "nombres", "Denied", "apellidos", "Patient", "telefonoWhatsapp", "+59170012345"));
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
