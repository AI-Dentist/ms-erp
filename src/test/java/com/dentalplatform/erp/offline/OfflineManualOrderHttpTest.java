package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.financebilling.application.usecase.CrearOrdenCobroManualUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.ActualizarServicioOdontologicoUseCase;
import com.dentalplatform.erp.offline.application.OfflineOperationStore;
import com.dentalplatform.erp.offline.domain.OfflineOperationReceipt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class OfflineManualOrderHttpTest extends OfflineOperationHttpTestSupport {
    @MockitoSpyBean CrearOrdenCobroManualUseCase orders;
    @MockitoSpyBean ActualizarServicioOdontologicoUseCase tariffs;
    @MockitoSpyBean OfflineOperationStore operations;
    private static final String FIELDS = "operationId type state payloadHash reasonCode confirmedAt result { patientId orderId installments { number id } }";
    private static final String SYNC = "mutation($input:OfflineOperationInput!){sincronizarOperacionOffline(input:$input){" + FIELDS + "}}";

    private JsonNode old(String query, Map<String, Object> args) throws Exception {
        var response = OfflineSyncHttpSupport.send(port, query, args, OfflineSyncHttpSupport.admin(), UUID.randomUUID().toString(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = OfflineSyncHttpSupport.json(response.body());
        assertThat(body.has("errors")).as(response.body()).isFalse();
        return body.path("data");
    }

    private Map<String, Object> patientData() {
        return Map.of("ciNit", UUID.randomUUID().toString().replace("-", "").substring(0, 24),
                "nombres", "Order", "apellidos", "Patient", "telefonoWhatsapp", "+59170012345");
    }

    private JsonNode patient(Map<String, Object> data) throws Exception {
        return old("mutation($input:CrearPacienteInput!){crearPaciente(input:$input){id ciNit}}", Map.of("input", data)).path("crearPaciente");
    }

    private JsonNode service() throws Exception {
        return old("mutation($input:CrearServicioInput!){crearServicioOdontologico(input:$input){id codigo nombre descripcion precioBaseBs}}",
                Map.of("input", Map.of("codigo", "of-" + UUID.randomUUID().toString().substring(0, 18),
                        "nombre", "Synthetic offline tariff", "descripcion", "Synthetic", "precioBaseBs", "120.50")))
                .path("crearServicioOdontologico");
    }

    private Map<String, Object> body(Map<String, Object> reference, JsonNode service) {
        return new HashMap<>(Map.of("patient", reference, "items", List.of(Map.of("servicioId", service.path("id").asText(),
                "piezaDental", "11", "cantidad", 2, "expectedUnitPriceBs", "120.50")),
                "descuentoBs", "1.00", "observaciones", "offline manual", "expectedTotalBs", "240.00"));
    }

    private JsonNode sync(Session session, UUID op, String type, String field, Map<String, Object> payload) throws Exception {
        return OfflineSyncHttpSupport.success(port, SYNC, Map.of("input", Map.of("sessionId", session.id().toString(),
                "operationId", op.toString(), "type", type, field, payload)), session.token()).path("sincronizarOperacionOffline");
    }

    private JsonNode order(Session session, UUID op, Map<String, Object> payload) throws Exception {
        return sync(session, op, "MANUAL_ORDER", "order", payload);
    }

    private JsonNode detail(JsonNode receipt) throws Exception {
        return old("query($id:UUID!){obtenerOrdenCobroDetalle(id:$id){id paciente{id} modalidadPago estado montoSubtotalBs descuentoBs montoTotalBs saldoPendienteBs cantidadCuotas items{servicio{id} cantidad precioUnitarioBs subtotalBs} cuotas{id numeroCuota montoCuotaBs estado}}}",
                Map.of("id", receipt.path("result").path("orderId").asText())).path("obtenerOrdenCobroDetalle");
    }

    private void confirmed(JsonNode receipt, String patientId, JsonNode service) throws Exception {
        assertThat(receipt.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(receipt.path("type").asText()).isEqualTo("MANUAL_ORDER");
        JsonNode data = detail(receipt);
        assertThat(data.path("paciente").path("id").asText()).isEqualTo(patientId);
        assertThat(data.path("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(data.path("modalidadPago").asText()).isEqualTo("CONTADO");
        assertThat(new BigDecimal(data.path("montoSubtotalBs").asText())).isEqualByComparingTo("241.00");
        assertThat(new BigDecimal(data.path("descuentoBs").asText())).isEqualByComparingTo("1.00");
        assertThat(new BigDecimal(data.path("montoTotalBs").asText())).isEqualByComparingTo("240.00");
        assertThat(new BigDecimal(data.path("saldoPendienteBs").asText())).isEqualByComparingTo("240.00");
        assertThat(data.path("items").get(0).path("servicio").path("id").asText()).isEqualTo(service.path("id").asText());
        assertThat(new BigDecimal(data.path("items").get(0).path("precioUnitarioBs").asText())).isEqualByComparingTo("120.50");
        assertThat(data.path("items").get(0).path("cantidad").asInt()).isEqualTo(2);
        assertThat(new BigDecimal(data.path("items").get(0).path("subtotalBs").asText())).isEqualByComparingTo("241.00");
        assertThat(data.path("cantidadCuotas").asInt()).isEqualTo(1);
        assertThat(data.path("cuotas").size()).isEqualTo(1);
        assertThat(data.path("cuotas").get(0).path("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(new BigDecimal(data.path("cuotas").get(0).path("montoCuotaBs").asText())).isEqualByComparingTo("240.00");
        assertThat(receipt.path("result").path("installments").get(0).path("number").asInt()).isEqualTo(1);
        assertThat(receipt.path("result").path("installments").get(0).path("id").asText())
                .isEqualTo(data.path("cuotas").get(0).path("id").asText());
    }

    private void conflict(JsonNode receipt, String state, String reason) {
        assertThat(receipt.path("state").asText()).isEqualTo(state);
        assertThat(receipt.path("reasonCode").asText()).isEqualTo(reason);
        assertThat(receipt.path("result").isNull()).isTrue();
    }

    private JsonNode reprice(JsonNode service) throws Exception {
        return old("mutation($id:UUID!,$input:ActualizarServicioInput!){actualizarServicioOdontologico(id:$id,input:$input){id precioBaseBs}}",
                Map.of("id", service.path("id").asText(), "input", Map.of("codigo", service.path("codigo").asText(),
                        "nombre", service.path("nombre").asText(), "descripcion", "Synthetic", "precioBaseBs", "121.50")));
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "CAJERO", "RECEPCIONISTA"})
    void allowedRolesCreateAnExactContadoOrderAndReplayStableMappings(String role) throws Exception {
        var session = session(role, true);
        var patient = patient(patientData());
        var service = service();
        var payload = body(Map.of("id", patient.path("id").asText()), service);
        UUID op = UUID.randomUUID();
        var before = OfflineSyncHttpSupport.snapshot(port);
        var result = order(session, op, payload);
        confirmed(result, patient.path("id").asText(), service);
        var after = OfflineSyncHttpSupport.snapshot(port);
        assertThat(after.path("listarOrdenesCobro").size()).isEqualTo(before.path("listarOrdenesCobro").size() + 1);
        assertThat(after.path("obtenerFlujoCaja")).isEqualTo(before.path("obtenerFlujoCaja"));
        assertThat(order(session, op, payload)).isEqualTo(result);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(after);
    }

    @ParameterizedTest @ValueSource(strings = {"price", "total", "discount", "precision"})
    void staleOrInvalidMoneyNeverCreatesAnOrder(String scenario) throws Exception {
        var session = session("ADMIN", true);
        var patient = patient(patientData());
        var service = service();
        var payload = body(Map.of("id", patient.path("id").asText()), service);
        switch (scenario) {
            case "price" -> reprice(service);
            case "total" -> payload.put("expectedTotalBs", "239.00");
            case "discount" -> payload.put("descuentoBs", "-0.001");
            case "precision" -> payload.put("expectedTotalBs", "240.001");
        }
        var before = OfflineSyncHttpSupport.snapshot(port);
        conflict(order(session, UUID.randomUUID(), payload), "CONFLICT",
                scenario.equals("price") || scenario.equals("total") ? "STALE_PRICE" : "INVALID_INPUT");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"patient", "service", "doctor"})
    void inactiveReferencesAreCheckedBeforeAnyBusinessWrite(String target) throws Exception {
        var session = session("ADMIN", true);
        var patient = patient(patientData());
        var service = service();
        var payload = body(Map.of("id", patient.path("id").asText()), service);
        String id = target.equals("patient") ? patient.path("id").asText() : service.path("id").asText();
        String operation = target.equals("patient") ? "eliminarPaciente" : "eliminarServicioOdontologico";
        if (target.equals("doctor")) {
            var doctors = old("{listarOdontologos(soloActivos:true){id}}", Map.of()).path("listarOdontologos");
            assertThat(doctors.size()).isPositive();
            id = doctors.get(0).path("id").asText();
            payload.put("odontologoId", id);
            operation = "eliminarOdontologo";
        }
        old("mutation($id:UUID!){" + operation + "(id:$id)}", Map.of("id", id));
        var before = OfflineSyncHttpSupport.snapshot(port);
        conflict(order(session, UUID.randomUUID(), payload), "CONFLICT", "INACTIVE_REFERENCE");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void unresolvedParentBlocksOnlyTheChildThenResolvesWithoutChangingItsHash() throws Exception {
        var session = session("ADMIN", true);
        var service = service();
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        var payload = body(Map.of("operationId", parent.toString()), service);
        var before = OfflineSyncHttpSupport.snapshot(port);
        var blocked = order(session, child, payload);
        conflict(blocked, "BLOCKED", "DEPENDENCY_PENDING");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
        var independent = sync(session, UUID.randomUUID(), "PATIENT_CREATE", "patient", patientData());
        assertThat(independent.path("state").asText()).isEqualTo("CONFIRMED");
        var created = sync(session, parent, "PATIENT_CREATE", "patient", patientData());
        var result = order(session, child, payload);
        confirmed(result, created.path("result").path("patientId").asText(), service);
        assertThat(result.path("payloadHash").asText()).isEqualTo(blocked.path("payloadHash").asText());
    }

    @Test
    void failedParentNeverGuessesAnotherPatientWithTheSameCi() throws Exception {
        var session = session("ADMIN", true);
        var data = patientData();
        patient(data);
        UUID parent = UUID.randomUUID();
        conflict(sync(session, parent, "PATIENT_CREATE", "patient", data), "CONFLICT", "PATIENT_DUPLICATE");
        var service = service();
        var before = OfflineSyncHttpSupport.snapshot(port);
        conflict(order(session, UUID.randomUUID(), body(Map.of("operationId", parent.toString()), service)),
                "BLOCKED", "DEPENDENCY_CONFLICT");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void parentMappingsAreNeverReadFromAnotherActor() throws Exception {
        var owner = session("ADMIN", true);
        UUID parent = UUID.randomUUID();
        assertThat(sync(owner, parent, "PATIENT_CREATE", "patient", patientData()).path("state").asText()).isEqualTo("CONFIRMED");
        var other = session("ADMIN", true);
        var service = service();
        var before = OfflineSyncHttpSupport.snapshot(port);
        conflict(order(other, UUID.randomUUID(), body(Map.of("operationId", parent.toString()), service)),
                "BLOCKED", "DEPENDENCY_PENDING");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void capturedTariffIsLockedAgainstTheExistingOnlineUpdater() throws Exception {
        var session = session("ADMIN", true);
        var patient = patient(patientData());
        var service = service();
        var payload = body(Map.of("id", patient.path("id").asText()), service);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var updating = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout"); return call.callRealMethod(); })
                .when(orders).ejecutar(any());
        doAnswer(call -> { updating.countDown(); return call.callRealMethod(); }).when(tariffs).ejecutar(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var order = executor.submit(() -> order(session, UUID.randomUUID(), payload));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var update = executor.submit(() -> reprice(service));
                assertThat(updating.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> update.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                var result = order.get(15, TimeUnit.SECONDS);
                update.get(15, TimeUnit.SECONDS);
                confirmed(result, patient.path("id").asText(), service);
            } finally { release.countDown(); }
        }
    }

    @Test
    void failureAfterConfirmationRollsBackOrderQuotaAndAuditNotThePatient() throws Exception {
        var session = session("ADMIN", true);
        var patient = patient(patientData());
        var service = service();
        var payload = body(Map.of("id", patient.path("id").asText()), service);
        UUID op = UUID.randomUUID();
        var failOnce = new AtomicBoolean(true);
        doAnswer(call -> {
            @SuppressWarnings("unchecked") Optional<OfflineOperationReceipt> value = (Optional<OfflineOperationReceipt>) call.callRealMethod();
            if (value.isPresent() && value.get().state() == OfflineOperationReceipt.State.CONFIRMED
                    && value.get().type() == OfflineOperationReceipt.Type.MANUAL_ORDER && failOnce.getAndSet(false)) {
                throw new TransientDataAccessResourceException("Synthetic after order confirmation SQL");
            }
            return value;
        }).when(operations).receipt(any(UUID.class), any(UUID.class));
        var before = OfflineSyncHttpSupport.snapshot(port);
        conflict(order(session, op, payload), "PREPARED", "TECHNICAL_RETRY_REQUIRED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
        confirmed(order(session, op, payload), patient.path("id").asText(), service);
    }
}
