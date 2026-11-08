package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.financebilling.application.command.CobrarEfectivoCommand;
import com.dentalplatform.erp.financebilling.application.usecase.CobrarCuotaEfectivoUseCase;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

class OfflineCashPaymentHttpTest extends OfflineOperationHttpTestSupport {
    @MockitoSpyBean CobrarCuotaEfectivoUseCase cash;
    @MockitoSpyBean OfflineOperationStore operations;
    private static final String FIELDS = "operationId type state payloadHash reasonCode confirmedAt result { orderId installments { number id } movementId receiptId }";
    private static final String SYNC = "mutation($input:OfflineOperationInput!){sincronizarOperacionOffline(input:$input){" + FIELDS + "}}";
    private static final String STATUS = "query($session:UUID!,$op:UUID!){estadoOperacionOffline(sessionId:$session,operationId:$op){" + FIELDS + "}}";
    private static final String FLOW = "{obtenerFlujoCaja(fechaInicio:\"1970-01-01T00:00:00Z\",fechaFin:\"9999-01-01T00:00:00Z\"){totalIngresosBs totalEgresosBs saldoNetoBs movimientos{id tipoMovimiento categoria metodoPago montoBs recibo{id ciNitCliente razonSocialCliente montoTotalBs}}}}";

    private JsonNode old(String query, Map<String, Object> args) throws Exception {
        var response = OfflineSyncHttpSupport.send(port, query, args, OfflineSyncHttpSupport.admin(), UUID.randomUUID().toString(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = OfflineSyncHttpSupport.json(response.body());
        assertThat(body.has("errors")).as(response.body()).isFalse();
        return body.path("data");
    }

    private JsonNode service() throws Exception {
        return old("mutation($input:CrearServicioInput!){crearServicioOdontologico(input:$input){id}}", Map.of("input", Map.of(
                "codigo", "cash-" + UUID.randomUUID().toString().substring(0, 18), "nombre", "Cash test",
                "descripcion", "Synthetic", "precioBaseBs", "120.50"))).path("crearServicioOdontologico");
    }

    private JsonNode patient() throws Exception {
        return old("mutation($input:CrearPacienteInput!){crearPaciente(input:$input){id}}", Map.of("input", Map.of(
                "ciNit", UUID.randomUUID().toString().replace("-", "").substring(0, 24), "nombres", "Cash",
                "apellidos", "Patient", "telefonoWhatsapp", "+59170012345"))).path("crearPaciente");
    }

    private JsonNode legacyOrder() throws Exception {
        var patient = patient();
        var service = service();
        return old("mutation($input:CrearOrdenCobroInput!){crearOrdenCobroManual(input:$input){id saldoPendienteBs cuotas{id numeroCuota montoCuotaBs estado}}}",
                Map.of("input", Map.of("pacienteId", patient.path("id").asText(), "items", List.of(Map.of(
                        "servicioId", service.path("id").asText(), "cantidad", 1))))).path("crearOrdenCobroManual");
    }

    private Map<String, Object> cashPayload(Map<String, Object> reference) {
        return new HashMap<>(Map.of("quota", reference, "expectedOrderState", "PENDIENTE", "expectedQuotaState", "PENDIENTE",
                "expectedOrderBalanceBs", "120.50", "expectedQuotaAmountBs", "120.50", "ciNitCliente", "1234567 SC",
                "razonSocialCliente", "Synthetic Customer", "requireOpenCashRegister", false));
    }

    private JsonNode sync(Session session, UUID id, String type, String field, Map<String, Object> payload) throws Exception {
        return OfflineSyncHttpSupport.success(port, SYNC, Map.of("input", Map.of("sessionId", session.id().toString(),
                "operationId", id.toString(), "type", type, field, payload)), session.token()).path("sincronizarOperacionOffline");
    }

    private JsonNode pay(Session session, UUID id, Map<String, Object> payload) throws Exception {
        return sync(session, id, "CASH_PAYMENT", "cash", payload);
    }

    private JsonNode status(Session session, UUID id) throws Exception {
        return OfflineSyncHttpSupport.success(port, STATUS, Map.of("session", session.id().toString(), "op", id.toString()),
                session.token()).path("estadoOperacionOffline");
    }

    private JsonNode flow() throws Exception {
        return OfflineSyncHttpSupport.success(port, FLOW, Map.of(), OfflineSyncHttpSupport.admin()).path("obtenerFlujoCaja");
    }

    private JsonNode detail(String id) throws Exception {
        return old("query($id:UUID!){obtenerOrdenCobroDetalle(id:$id){id estado saldoPendienteBs cuotas{id numeroCuota montoCuotaBs estado}}}",
                Map.of("id", id)).path("obtenerOrdenCobroDetalle");
    }

    private BigDecimal decimal(JsonNode node, String field) { return new BigDecimal(node.path(field).asText()); }

    private void rejected(JsonNode receipt, String state, String reason) {
        assertThat(receipt.path("state").asText()).isEqualTo(state);
        assertThat(receipt.path("reasonCode").asText()).isEqualTo(reason);
        assertThat(receipt.path("result").isNull()).isTrue();
    }

    private void paid(JsonNode result, JsonNode order, JsonNode beforeFlow) throws Exception {
        assertThat(result.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(result.path("type").asText()).isEqualTo("CASH_PAYMENT");
        assertThat(result.path("reasonCode").isNull()).isTrue();
        assertThat(result.path("confirmedAt").asText()).isNotBlank();
        assertThat(result.path("payloadHash").asText()).matches("[0-9a-f]{64}");
        assertThat(result.path("result").path("orderId").asText()).isEqualTo(order.path("id").asText());
        var current = detail(order.path("id").asText());
        assertThat(current.path("estado").asText()).isEqualTo("PAGADO");
        assertThat(decimal(current, "saldoPendienteBs")).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(current.path("cuotas").get(0).path("estado").asText()).isEqualTo("PAGADA");
        assertThat(decimal(current.path("cuotas").get(0), "montoCuotaBs")).isEqualByComparingTo("120.50");
        var after = flow();
        assertThat(decimal(after, "totalIngresosBs").subtract(decimal(beforeFlow, "totalIngresosBs"))).isEqualByComparingTo("120.50");
        assertThat(decimal(after, "totalEgresosBs")).isEqualByComparingTo(decimal(beforeFlow, "totalEgresosBs"));
        assertThat(after.path("movimientos").size()).isEqualTo(beforeFlow.path("movimientos").size() + 1);
        JsonNode movement = null;
        for (JsonNode item : after.path("movimientos")) {
            if (item.path("id").asText().equals(result.path("result").path("movementId").asText())) movement = item;
        }
        assertThat(movement).isNotNull();
        assertThat(movement.path("tipoMovimiento").asText()).isEqualTo("INGRESO");
        assertThat(movement.path("metodoPago").asText()).isEqualTo("EFECTIVO");
        assertThat(decimal(movement, "montoBs")).isEqualByComparingTo("120.50");
        assertThat(movement.path("recibo").path("id").asText()).isEqualTo(result.path("result").path("receiptId").asText());
        assertThat(movement.path("recibo").path("ciNitCliente").asText()).isEqualTo("1234567 SC");
        assertThat(movement.path("recibo").path("razonSocialCliente").asText()).isEqualTo("Synthetic Customer");
        assertThat(decimal(movement.path("recibo"), "montoTotalBs")).isEqualByComparingTo("120.50");
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "CAJERO"})
    void cashUsesCanonicalActorAndLostAckReplaysOneRealPayment(String role) throws Exception {
        var session = session(role, true);
        var order = legacyOrder();
        var payload = cashPayload(Map.of("id", order.path("cuotas").get(0).path("id").asText()));
        var captured = new AtomicReference<CobrarEfectivoCommand>();
        doAnswer(call -> { captured.set(call.getArgument(0)); return call.callRealMethod(); }).when(cash).ejecutar(any());
        UUID id = UUID.randomUUID();
        var before = flow();
        var receipt = pay(session, id, payload);
        paid(receipt, order, before);
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().usuarioCajeroId().toString()).isEqualTo(session.actor().id());
        assertThat(captured.get().cuotaId().toString()).isEqualTo(order.path("cuotas").get(0).path("id").asText());
        var after = OfflineSyncHttpSupport.snapshot(port);
        assertThat(pay(session, id, payload)).isEqualTo(receipt);
        assertThat(status(session, id)).isEqualTo(receipt);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(after);
    }

    @Test
    void receptionistCannotPayOrCreateAnIntent() throws Exception {
        var session = session("RECEPCIONISTA", true);
        var order = legacyOrder();
        var payload = cashPayload(Map.of("id", order.path("cuotas").get(0).path("id").asText()));
        UUID id = UUID.randomUUID();
        var before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, SYNC, Map.of("input", Map.of("sessionId", session.id().toString(),
                "operationId", id.toString(), "type", "CASH_PAYMENT", "cash", payload)), session.token(), null, null);
        OfflineSyncHttpSupport.error(response, "PERMISSION_REVOKED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"order", "balance", "quota", "amount", "open", "precision", "overflow"})
    void staleExpectationsAndUnmodelledOpenRegisterNeverWrite(String scenario) throws Exception {
        var session = session("ADMIN", true);
        var order = legacyOrder();
        var payload = cashPayload(Map.of("id", order.path("cuotas").get(0).path("id").asText()));
        switch (scenario) {
            case "order" -> payload.put("expectedOrderState", "PARCIAL");
            case "balance" -> payload.put("expectedOrderBalanceBs", "120.51");
            case "quota" -> payload.put("expectedQuotaState", "PAGADA");
            case "amount" -> payload.put("expectedQuotaAmountBs", "120.51");
            case "open" -> payload.put("requireOpenCashRegister", true);
            case "precision" -> payload.put("expectedQuotaAmountBs", "120.501");
            case "overflow" -> payload.put("expectedOrderBalanceBs", "10000000000.00");
        }
        var before = OfflineSyncHttpSupport.snapshot(port);
        UUID id = UUID.randomUUID();
        var result = pay(session, id, payload);
        rejected(result, "CONFLICT", switch (scenario) {
            case "order", "balance" -> "STALE_ORDER";
            case "quota", "amount" -> "STALE_QUOTA";
            case "open" -> "CASH_REGISTER_STATE_NOT_MODELLED";
            default -> "INVALID_INPUT";
        });
        assertThat(status(session, id)).isEqualTo(result);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void pendingOwnOrderResolvesNumberOneWithoutChangingFrozenHash() throws Exception {
        var session = session("ADMIN", true);
        var patient = patient();
        var service = service();
        UUID parent = UUID.randomUUID();
        UUID child = UUID.randomUUID();
        var payload = cashPayload(Map.of("operationId", parent.toString(), "installmentNumber", 1));
        var before = OfflineSyncHttpSupport.snapshot(port);
        var blocked = pay(session, child, payload);
        rejected(blocked, "BLOCKED", "DEPENDENCY_PENDING");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
        var created = sync(session, parent, "MANUAL_ORDER", "order", Map.of("patient", Map.of("id", patient.path("id").asText()),
                "items", List.of(Map.of("servicioId", service.path("id").asText(), "cantidad", 1, "expectedUnitPriceBs", "120.50")),
                "expectedTotalBs", "120.50"));
        assertThat(created.path("state").asText()).isEqualTo("CONFIRMED");
        var order = detail(created.path("result").path("orderId").asText());
        assertThat(created.path("result").path("installments").get(0).path("id").asText()).isEqualTo(order.path("cuotas").get(0).path("id").asText());
        var flow = flow();
        var result = pay(session, child, payload);
        paid(result, order, flow);
        assertThat(result.path("payloadHash").asText()).isEqualTo(blocked.path("payloadHash").asText());
    }

    @Test
    void foreignAndMalformedReferencesDoNotResolveOrWrite() throws Exception {
        var owner = session("ADMIN", true);
        var other = session("ADMIN", true);
        var patient = patient();
        var service = service();
        UUID parent = UUID.randomUUID();
        var created = sync(owner, parent, "MANUAL_ORDER", "order", Map.of("patient", Map.of("id", patient.path("id").asText()),
                "items", List.of(Map.of("servicioId", service.path("id").asText(), "cantidad", 1, "expectedUnitPriceBs", "120.50")),
                "expectedTotalBs", "120.50"));
        assertThat(created.path("state").asText()).isEqualTo("CONFIRMED");
        var before = OfflineSyncHttpSupport.snapshot(port);
        rejected(pay(other, UUID.randomUUID(), cashPayload(Map.of("operationId", parent.toString(), "installmentNumber", 1))), "BLOCKED", "DEPENDENCY_PENDING");
        rejected(pay(owner, UUID.randomUUID(), cashPayload(Map.of("operationId", parent.toString(), "installmentNumber", 2))), "CONFLICT", "INVALID_INPUT");
        rejected(pay(owner, UUID.randomUUID(), cashPayload(Map.of("id", UUID.randomUUID().toString(), "operationId", parent.toString(), "installmentNumber", 1))), "CONFLICT", "INVALID_INPUT");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void conflictedParentBlocksPaymentWithoutGuessingAnotherQuota() throws Exception {
        var session = session("ADMIN", true);
        var patient = patient();
        var service = service();
        var unrelated = legacyOrder();
        UUID parent = UUID.randomUUID();
        var parentResult = sync(session, parent, "MANUAL_ORDER", "order", Map.of("patient", Map.of("id", patient.path("id").asText()),
                "items", List.of(Map.of("servicioId", service.path("id").asText(), "cantidad", 1, "expectedUnitPriceBs", "120.50")),
                "expectedTotalBs", "121.50"));
        rejected(parentResult, "CONFLICT", "STALE_PRICE");
        var before = OfflineSyncHttpSupport.snapshot(port);
        var result = pay(session, UUID.randomUUID(), cashPayload(Map.of("operationId", parent.toString(), "installmentNumber", 1)));
        rejected(result, "BLOCKED", "DEPENDENCY_CONFLICT");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
        assertThat(detail(unrelated.path("id").asText()).path("estado").asText()).isEqualTo("PENDIENTE");
    }

    @Test
    void clientCannotSupplyCashierField() throws Exception {
        var session = session("ADMIN", true);
        var order = legacyOrder();
        var payload = cashPayload(Map.of("id", order.path("cuotas").get(0).path("id").asText()));
        payload.put("usuarioCajeroId", UUID.randomUUID().toString());
        var before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, SYNC, Map.of("input", Map.of("sessionId", session.id().toString(),
                "operationId", UUID.randomUUID().toString(), "type", "CASH_PAYMENT", "cash", payload)), session.token(), null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(OfflineSyncHttpSupport.json(response.body()).path("errors").isArray()).isTrue();
        assertThat(response.body()).contains("usuarioCajeroId");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void concurrentOfflineSessionsForSameQuotaHaveOneWinner() throws Exception {
        var first = session("ADMIN", true);
        var second = renew(first);
        var order = legacyOrder();
        var payload = cashPayload(Map.of("id", order.path("cuotas").get(0).path("id").asText()));
        var before = flow();
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> { ready.countDown(); if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout"); return pay(first, UUID.randomUUID(), payload); });
            var right = executor.submit(() -> { ready.countDown(); if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout"); return pay(second, UUID.randomUUID(), payload); });
            try {
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                go.countDown();
                var results = List.of(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
                assertThat(results.stream().map(result -> result.path("state").asText()).toList()).containsExactlyInAnyOrder("CONFIRMED", "CONFLICT");
                var winner = results.stream().filter(result -> result.path("state").asText().equals("CONFIRMED")).findFirst().orElseThrow();
                var loser = results.stream().filter(result -> result.path("state").asText().equals("CONFLICT")).findFirst().orElseThrow();
                rejected(loser, "CONFLICT", "STALE_ORDER");
                paid(winner, order, before);
            } finally { go.countDown(); }
        }
    }

    @Test
    void afterConfirmationSqlFailureRollsBackPaymentAndSameFrozenIntentRetriesOnce() throws Exception {
        var session = session("ADMIN", true);
        var order = legacyOrder();
        var payload = cashPayload(Map.of("id", order.path("cuotas").get(0).path("id").asText()));
        var fail = new AtomicBoolean(true);
        doAnswer(call -> {
            @SuppressWarnings("unchecked") Optional<OfflineOperationReceipt> value = (Optional<OfflineOperationReceipt>) call.callRealMethod();
            if (value.isPresent() && value.get().state() == OfflineOperationReceipt.State.CONFIRMED
                    && value.get().type() == OfflineOperationReceipt.Type.CASH_PAYMENT && fail.getAndSet(false)) {
                throw new TransientDataAccessResourceException("Synthetic after cash confirmation SQL");
            }
            return value;
        }).when(operations).receipt(any(UUID.class), any(UUID.class));
        UUID id = UUID.randomUUID();
        var before = OfflineSyncHttpSupport.snapshot(port);
        var flow = flow();
        var failed = pay(session, id, payload);
        rejected(failed, "PREPARED", "TECHNICAL_RETRY_REQUIRED");
        assertThat(status(session, id)).isEqualTo(failed);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
        var recovered = pay(session, id, payload);
        paid(recovered, order, flow);
        assertThat(recovered.path("payloadHash").asText()).isEqualTo(failed.path("payloadHash").asText());
        var after = OfflineSyncHttpSupport.snapshot(port);
        assertThat(pay(session, id, payload)).isEqualTo(recovered);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(after);
    }

    @Test
    void offlineCashHoldsOrderLockAgainstTheRealOnlineCashCase() throws Exception {
        var session = session("ADMIN", true);
        var order = legacyOrder();
        String quota = order.path("cuotas").get(0).path("id").asText();
        var payload = cashPayload(Map.of("id", quota));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var onlineEntered = new CountDownLatch(1);
        doAnswer(call -> {
            CobrarEfectivoCommand command = call.getArgument(0);
            if (command.usuarioCajeroId().toString().equals(session.actor().id())) {
                entered.countDown();
                if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout");
            } else onlineEntered.countDown();
            return call.callRealMethod();
        }).when(cash).ejecutar(any());
        var before = flow();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var offline = executor.submit(() -> pay(session, UUID.randomUUID(), payload));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var online = executor.submit(() -> OfflineSyncHttpSupport.send(port,
                        "mutation($input:CobrarEfectivoInput!){cobrarCuotaEnEfectivo(input:$input){id}}",
                        Map.of("input", Map.of("cuotaId", quota, "usuarioCajeroId", OfflineSyncHttpSupport.createAdmin(port).id(),
                                "ciNitCliente", "1234567 SC", "razonSocialCliente", "Synthetic Customer")),
                        OfflineSyncHttpSupport.admin(), UUID.randomUUID().toString(), null));
                assertThat(onlineEntered.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> online.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                var result = offline.get(20, TimeUnit.SECONDS);
                var rejected = online.get(20, TimeUnit.SECONDS);
                assertThat(rejected.statusCode()).isEqualTo(200);
                assertThat(OfflineSyncHttpSupport.json(rejected.body()).path("errors").isArray()).isTrue();
                paid(result, order, before);
            } finally { release.countDown(); }
        }
    }
}
