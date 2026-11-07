package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.offline.application.ExecuteOfflineOperationUseCase;
import com.dentalplatform.erp.offline.domain.OfflineSyncFailure;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/** A denial must leave an immutable, durable record that still refuses altered intent. */
class OfflineSyncDenialHttpTest extends OfflineOperationHttpTestSupport {
    @MockitoSpyBean ExecuteOfflineOperationUseCase execution;
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
