package com.dentalplatform.erp.offline;

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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class OfflineSyncBoundaryLegacyHttpTest {
    @LocalServerPort int port;
    @MockitoBean RabbitTemplate rabbit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        OfflineSyncHttpSupport.configure(registry, "erp_offline_sync_boundary");
    }

    private String key() { return UUID.randomUUID().toString(); }

    @ParameterizedTest
    @ValueSource(strings = {
            "mutation { prepararSyncOffline(input: {}) { sessionId } }",
            "mutation { alias: confirmarSyncOffline(sessionId: \"00000000-0000-0000-0000-000000000000\") { sessionId } }",
            "mutation { ...Sync } fragment Sync on Mutation { sincronizarOperacionOffline(input: {}) { state } }",
            "query { estadoOperacionOffline(sessionId: \"00000000-0000-0000-0000-000000000000\", operationId: \"00000000-0000-0000-0000-000000000000\") { state } }"
    })
    void selectedOfflineRootsRejectLegacyKeyBeforeExecution(String query) throws Exception {
        var before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, query, Map.of(), OfflineSyncHttpSupport.admin(), key(), null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("OFFLINE_LEGACY_KEY_NOT_ALLOWED");
        assertThat(response.headers().firstValue("Idempotency-Replayed")).isEmpty();
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void mixedBusinessAndOfflineRootsAreRejectedWithoutWrites() throws Exception {
        var before = OfflineSyncHttpSupport.snapshot(port);
        String query = "mutation { prepararSyncOffline(input: {}) { sessionId } crearPaciente(input: {}) { id } }";
        var response = OfflineSyncHttpSupport.send(port, query, Map.of(), OfflineSyncHttpSupport.admin(), null, null);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("OFFLINE_MIXED_OPERATION");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void legacyMutationsReplayOnceWithDelegatedCashier() throws Exception {
        var actor = OfflineSyncHttpSupport.createAdmin(port);
        String token = OfflineSyncHttpSupport.token(actor);
        JsonNode seeds = OfflineSyncHttpSupport.success(port,
                "{ listarUsuarios { id activo } listarServiciosOdontologicos { id activo precioBaseBs } }",
                Map.of(), token);
        JsonNode cashier = null;
        for (JsonNode user : seeds.path("listarUsuarios")) {
            if (user.path("activo").asBoolean() && !user.path("id").asText().equals(actor.id())) {
                cashier = user;
                break;
            }
        }
        assertThat(cashier).isNotNull();
        JsonNode service = null;
        for (JsonNode candidate : seeds.path("listarServiciosOdontologicos")) {
            if (candidate.path("activo").asBoolean()
                    && new BigDecimal(candidate.path("precioBaseBs").asText()).signum() > 0) {
                service = candidate;
                break;
            }
        }
        assertThat(service).isNotNull();
        String cashierId = cashier.path("id").asText();
        var before = OfflineSyncHttpSupport.snapshot(port);
        JsonNode patient = replay(token, "mutation($input: CrearPacienteInput!) { crearPaciente(input:$input) { id } }",
                Map.of("ciNit", UUID.randomUUID().toString().replace("-", "").substring(0, 24), "nombres", "Paciente", "apellidos", "Sintetico",
                        "telefonoWhatsapp", "+59170012345"), "crearPaciente");
        var afterPatient = OfflineSyncHttpSupport.snapshot(port);
        assertThat(afterPatient.path("listarPacientes").size()).isEqualTo(before.path("listarPacientes").size() + 1);
        JsonNode order = replay(token, "mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input:$input) { id saldoPendienteBs cuotas { id } } }",
                Map.of("pacienteId", patient.path("id").asText(), "items", List.of(Map.of(
                        "servicioId", service.path("id").asText(), "cantidad", 1))), "crearOrdenCobroManual");
        var afterOrder = OfflineSyncHttpSupport.snapshot(port);
        assertThat(afterOrder.path("listarOrdenesCobro").size()).isEqualTo(afterPatient.path("listarOrdenesCobro").size() + 1);
        BigDecimal amount = new BigDecimal(order.path("saldoPendienteBs").asText());
        assertThat(amount).isPositive();
        assertThat(order.path("cuotas").size()).isEqualTo(1);
        replay(token, "mutation($input: CobrarEfectivoInput!) { cobrarCuotaEnEfectivo(input:$input) { id } }",
                Map.of("cuotaId", order.path("cuotas").get(0).path("id").asText(), "usuarioCajeroId", cashierId,
                        "ciNitCliente", "1234567 SC", "razonSocialCliente", "Paciente Sintetico"), "cobrarCuotaEnEfectivo");
        var afterCash = OfflineSyncHttpSupport.snapshot(port);
        assertThat(afterCash.path("obtenerFlujoCaja").path("movimientos").size())
                .isEqualTo(afterOrder.path("obtenerFlujoCaja").path("movimientos").size() + 1);
        assertThat(decimal(afterCash, "totalIngresosBs").subtract(decimal(afterOrder, "totalIngresosBs")))
                .isEqualByComparingTo(amount);
        JsonNode paid = null;
        for (JsonNode entry : afterCash.path("listarOrdenesCobro")) {
            if (entry.path("id").asText().equals(order.path("id").asText())) paid = entry;
        }
        assertThat(paid).isNotNull();
        assertThat(paid.path("estado").asText()).isEqualTo("PAGADO");
        assertThat(new BigDecimal(paid.path("saldoPendienteBs").asText())).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(paid.path("cuotas").get(0).path("estado").asText()).isEqualTo("PAGADA");
        replay(token, "mutation($input: RegistrarEgresoInput!) { registrarEgresoCaja(input:$input) { id } }",
                Map.of("usuarioCajeroId", cashierId, "categoria", "INSUMOS", "metodoPago", "EFECTIVO",
                        "montoBs", new BigDecimal("45.50"), "descripcion", "Insumos sinteticos"), "registrarEgresoCaja");
        var afterExpense = OfflineSyncHttpSupport.snapshot(port);
        assertThat(afterExpense.path("obtenerFlujoCaja").path("movimientos").size())
                .isEqualTo(afterCash.path("obtenerFlujoCaja").path("movimientos").size() + 1);
        assertThat(decimal(afterExpense, "totalEgresosBs").subtract(decimal(afterCash, "totalEgresosBs")))
                .isEqualByComparingTo("45.50");
    }

    private BigDecimal decimal(JsonNode snapshot, String field) {
        return new BigDecimal(snapshot.path("obtenerFlujoCaja").path(field).asText());
    }

    private JsonNode replay(String token, String query, Map<String, Object> input, String field) throws Exception {
        String key = key();
        Map<String, Object> variables = Map.of("input", input);
        var first = OfflineSyncHttpSupport.send(port, query, variables, token, key, null);
        assertThat(first.statusCode()).isEqualTo(200);
        assertThat(first.headers().firstValue("Idempotency-Replayed")).isEmpty();
        JsonNode body = OfflineSyncHttpSupport.json(first.body());
        assertThat(body.has("errors")).as(first.body()).isFalse();
        JsonNode result = body.path("data").path(field);
        assertThat(UUID.fromString(result.path("id").asText())).isNotNull();
        var afterFirst = OfflineSyncHttpSupport.snapshot(port);
        var second = OfflineSyncHttpSupport.send(port, query, variables, token, key, null);
        assertThat(second.statusCode()).isEqualTo(200);
        assertThat(second.headers().firstValue("Idempotency-Replayed")).contains("true");
        assertThat(second.body()).isEqualTo(first.body());
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(afterFirst);
        return result;
    }

    @Test
    void selectedLegacyOperationIgnoresUnselectedOfflineOperation() throws Exception {
        String query = "query Old { __typename } query New { estadoOperacionOffline(sessionId: \"00000000-0000-0000-0000-000000000000\", operationId: \"00000000-0000-0000-0000-000000000000\") { state } }";
        var response = OfflineSyncHttpSupport.send(port, query, Map.of(), OfflineSyncHttpSupport.admin(), key(), "Old");
        assertThat(response.statusCode()).isEqualTo(200);
        // GraphQL validation may still reject an unknown field in the unselected definition.
        assertThat(response.body()).doesNotContain("OFFLINE_LEGACY_KEY_NOT_ALLOWED", "OFFLINE_MIXED_OPERATION");
    }

    @Test
    void stringLiteralIsNotAnOfflineRoot() throws Exception {
        String query = "query { __type(name: \"prepararSyncOffline\") { name } }";
        var response = OfflineSyncHttpSupport.send(port, query, Map.of(), OfflineSyncHttpSupport.admin(), key(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(OfflineSyncHttpSupport.json(response.body()).has("errors")).isFalse();
        assertThat(response.body()).doesNotContain("OFFLINE_LEGACY_KEY_NOT_ALLOWED", "OFFLINE_MIXED_OPERATION");
    }

    @ParameterizedTest
    @ValueSource(strings = {"query { nonexistentLegacyField }", "mutation { broken("})
    void invalidLegacyRequestsAreNotRewritten(String query) throws Exception {
        var before = OfflineSyncHttpSupport.snapshot(port);
        var withoutKey = OfflineSyncHttpSupport.send(port, query, Map.of(), OfflineSyncHttpSupport.admin(), null, null);
        var withKey = OfflineSyncHttpSupport.send(port, query, Map.of(), OfflineSyncHttpSupport.admin(), key(), null);
        assertThat(withKey.statusCode()).isEqualTo(withoutKey.statusCode());
        assertThat(withKey.body()).isEqualTo(withoutKey.body());
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }
}
