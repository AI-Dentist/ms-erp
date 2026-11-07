package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.financebilling.application.command.RegistrarEgresoCommand;
import com.dentalplatform.erp.financebilling.application.usecase.RegistrarEgresoCajaUseCase;
import com.dentalplatform.erp.financebilling.domain.repository.MovimientoCajaRepository;
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

class OfflineCashExpenseHttpTest extends OfflineOperationHttpTestSupport {
    @MockitoSpyBean RegistrarEgresoCajaUseCase expenses;
    @MockitoSpyBean OfflineOperationStore operations;
    @MockitoSpyBean MovimientoCajaRepository movements;
    private static final String FIELDS = "operationId type state payloadHash reasonCode confirmedAt result { movementId receiptId }";
    private static final String SYNC = "mutation($input:OfflineOperationInput!){sincronizarOperacionOffline(input:$input){" + FIELDS + "}}";
    private static final String STATUS = "query($session:UUID!,$op:UUID!){estadoOperacionOffline(sessionId:$session,operationId:$op){" + FIELDS + "}}";

    private BigDecimal balance(JsonNode snapshot) {
        return new BigDecimal(snapshot.path("obtenerFlujoCaja").path("saldoNetoBs").asText());
    }

    private Map<String, Object> payload(BigDecimal balance, String amount) {
        return new HashMap<>(Map.of("categoria", "Offline supplies", "metodoPago", "TRANSFERENCIA",
                "montoBs", amount, "descripcion", "Synthetic expense",
                "expectedCashflowBalanceBs", balance.toPlainString(), "requireOpenCashRegister", false));
    }

    private JsonNode sync(Session session, UUID op, Map<String, Object> payload) throws Exception {
        return OfflineSyncHttpSupport.success(port, SYNC, Map.of("input", Map.of("sessionId", session.id().toString(),
                "operationId", op.toString(), "type", "CASH_EXPENSE", "expense", payload)), session.token())
                .path("sincronizarOperacionOffline");
    }

    private JsonNode status(Session session, UUID op) throws Exception {
        return OfflineSyncHttpSupport.success(port, STATUS, Map.of("session", session.id().toString(),
                "op", op.toString()), session.token()).path("estadoOperacionOffline");
    }

    private JsonNode legacy(String cashier, String amount) throws Exception {
        var response = OfflineSyncHttpSupport.send(port, """
                mutation($input:RegistrarEgresoInput!){registrarEgresoCaja(input:$input){id tipoMovimiento categoria metodoPago montoBs descripcion}}
                """, Map.of("input", Map.of("usuarioCajeroId", cashier, "categoria", "Legacy supplies",
                "metodoPago", "EFECTIVO", "montoBs", amount, "descripcion", "Legacy expense")),
                OfflineSyncHttpSupport.admin(), UUID.randomUUID().toString(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var json = OfflineSyncHttpSupport.json(response.body());
        assertThat(json.has("errors")).as(response.body()).isFalse();
        return json.path("data").path("registrarEgresoCaja");
    }

    private void conflict(JsonNode result, String state, String reason) {
        assertThat(result.path("state").asText()).isEqualTo(state);
        assertThat(result.path("reasonCode").asText()).isEqualTo(reason);
        assertThat(result.path("result").isNull()).isTrue();
    }

    private void confirmed(JsonNode result, UUID op, JsonNode before, String amount) throws Exception {
        assertThat(result.path("operationId").asText()).isEqualTo(op.toString());
        assertThat(result.path("type").asText()).isEqualTo("CASH_EXPENSE");
        assertThat(result.path("state").asText()).isEqualTo("CONFIRMED");
        assertThat(result.path("reasonCode").isNull()).isTrue();
        assertThat(result.path("payloadHash").asText()).matches("[0-9a-f]{64}");
        assertThat(result.path("confirmedAt").asText()).isNotBlank();
        String id = result.path("result").path("movementId").asText();
        assertThat(UUID.fromString(id)).isNotNull();
        assertThat(result.path("result").path("receiptId").isNull()).isTrue();
        var details = OfflineSyncHttpSupport.success(port, """
                {obtenerFlujoCaja(fechaInicio:"1970-01-01T00:00:00Z",fechaFin:"9999-01-01T00:00:00Z") {
                  movimientos { id categoria metodoPago descripcion } }}
                """, Map.of(), OfflineSyncHttpSupport.admin()).path("obtenerFlujoCaja").path("movimientos");
        JsonNode detail = null;
        for (JsonNode movement : details) if (id.equals(movement.path("id").asText())) detail = movement;
        assertThat(detail).isNotNull();
        assertThat(detail.path("categoria").asText()).isEqualTo("Offline supplies");
        assertThat(detail.path("metodoPago").asText()).isEqualTo("TRANSFERENCIA");
        assertThat(detail.path("descripcion").asText()).isEqualTo("Synthetic expense");
        var after = OfflineSyncHttpSupport.snapshot(port);
        var flow = after.path("obtenerFlujoCaja");
        assertThat(flow.path("movimientos").size()).isEqualTo(before.path("obtenerFlujoCaja").path("movimientos").size() + 1);
        assertThat(balance(after)).isEqualByComparingTo(balance(before).subtract(new BigDecimal(amount)));
        JsonNode found = null;
        for (JsonNode movement : flow.path("movimientos")) if (id.equals(movement.path("id").asText())) found = movement;
        assertThat(found).isNotNull();
        assertThat(found.path("tipoMovimiento").asText()).isEqualTo("EGRESO");
        assertThat(new BigDecimal(found.path("montoBs").asText())).isEqualByComparingTo(amount);
        assertThat(after.path("listarAuditLogs").size()).isEqualTo(before.path("listarAuditLogs").size() + 1);
    }

    @ParameterizedTest @ValueSource(strings = {"ADMIN", "CAJERO"})
    void canonicalCashierCreatesMovementAndLostAckReplaysAfterBalanceChanges(String role) throws Exception {
        var session = session(role, true);
        var before = OfflineSyncHttpSupport.snapshot(port);
        var input = payload(balance(before), "1.25");
        var captured = new AtomicReference<RegistrarEgresoCommand>();
        doAnswer(call -> { captured.set(call.getArgument(0)); return call.callRealMethod(); }).when(expenses).ejecutar(any());
        UUID op = UUID.randomUUID();
        var first = sync(session, op, input);
        confirmed(first, op, before, "1.25");
        assertThat(captured.get().usuarioCajeroId()).isEqualTo(UUID.fromString(session.actor().id()));
        assertThat(captured.get().metodoPago().name()).isEqualTo("TRANSFERENCIA");
        legacy(session.actor().id(), "0.01");
        var changed = OfflineSyncHttpSupport.snapshot(port);
        assertThat(sync(session, op, input)).isEqualTo(first);
        assertThat(status(session, op)).isEqualTo(first);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(changed);
    }

    @Test
    void receptionistCannotExecuteExpense() throws Exception {
        var session = session("RECEPCIONISTA", true);
        var before = OfflineSyncHttpSupport.snapshot(port);
        var response = OfflineSyncHttpSupport.send(port, SYNC, Map.of("input", Map.of("sessionId", session.id().toString(),
                "operationId", UUID.randomUUID().toString(), "type", "CASH_EXPENSE", "expense", payload(balance(before), "1.00"))),
                session.token(), null, null);
        OfflineSyncHttpSupport.error(response, "PERMISSION_REVOKED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void staleBalanceAndUnmodelledOpenRegisterHaveNoEffects() throws Exception {
        var session = session("ADMIN", true);
        var before = OfflineSyncHttpSupport.snapshot(port);
        var stale = payload(balance(before).add(BigDecimal.ONE), "1.00");
        conflict(sync(session, UUID.randomUUID(), stale), "CONFLICT", "STALE_CASHFLOW_BALANCE");
        var open = payload(balance(before), "1.00");
        open.put("requireOpenCashRegister", true);
        conflict(sync(session, UUID.randomUUID(), open), "CONFLICT", "CASH_REGISTER_STATE_NOT_MODELLED");
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @ParameterizedTest @ValueSource(strings = {"0", "-1", "0.001", "10000000000.00"})
    void invalidAmountNeverWritesOrRounds(String amount) throws Exception {
        var session = session("ADMIN", true);
        var before = OfflineSyncHttpSupport.snapshot(port);
        UUID op = UUID.randomUUID();
        var result = sync(session, op, payload(balance(before), amount));
        conflict(result, "CONFLICT", "INVALID_INPUT");
        assertThat(status(session, op)).isEqualTo(result);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void negativeBalanceAndExpenseExceedingAvailableBalanceAreAllowed() throws Exception {
        var session = session("ADMIN", true);
        var initial = OfflineSyncHttpSupport.snapshot(port);
        BigDecimal remaining = balance(initial).max(BigDecimal.ZERO).add(BigDecimal.ONE);
        BigDecimal max = new BigDecimal("9999999999.99");
        // Bounded public fixtures: no single debit exceeds the movement-column limit.
        for (int i = 0; remaining.signum() > 0 && i < 100; i++) {
            BigDecimal part = remaining.min(max);
            legacy(session.actor().id(), part.toPlainString());
            remaining = remaining.subtract(part);
        }
        assertThat(remaining).as("fixture requires at most 100 public debits").isEqualByComparingTo(BigDecimal.ZERO);
        var before = OfflineSyncHttpSupport.snapshot(port);
        assertThat(balance(before)).isNegative();
        UUID op = UUID.randomUUID();
        confirmed(sync(session, op, payload(balance(before), "1.00")), op, before, "1.00");
    }

    @Test
    void aggregateBeyondRecordLimitRemainsAValidExpectedBalance() throws Exception {
        var session = session("ADMIN", true);
        // Two maximum-sized public debits make the absolute balance exceed a single-record limit
        // regardless of a modest initial positive balance, without constraining the expected balance.
        var initial = OfflineSyncHttpSupport.snapshot(port);
        BigDecimal max = new BigDecimal("9999999999.99");
        BigDecimal target = balance(initial).add(max).add(BigDecimal.ONE).max(BigDecimal.ZERO);
        for (int i = 0; target.signum() > 0 && i < 100; i++) {
            BigDecimal part = target.min(max);
            legacy(session.actor().id(), part.toPlainString());
            target = target.subtract(part);
        }
        assertThat(target).isEqualByComparingTo(BigDecimal.ZERO);
        var before = OfflineSyncHttpSupport.snapshot(port);
        assertThat(balance(before)).isLessThan(max.negate());
        UUID op = UUID.randomUUID();
        confirmed(sync(session, op, payload(balance(before), "1.00")), op, before, "1.00");
    }

    @ParameterizedTest @ValueSource(strings = {"emptyCategory", "longCategory", "emptyDescription"})
    void invalidDomainTextNeverWrites(String scenario) throws Exception {
        var session = session("ADMIN", true);
        var before = OfflineSyncHttpSupport.snapshot(port);
        var input = payload(balance(before), "1.00");
        switch (scenario) {
            case "emptyCategory" -> input.put("categoria", "  ");
            case "longCategory" -> input.put("categoria", "x".repeat(51));
            case "emptyDescription" -> input.put("descripcion", "  ");
        }
        UUID op = UUID.randomUUID();
        var result = sync(session, op, input);
        conflict(result, "CONFLICT", "INVALID_INPUT");
        assertThat(status(session, op)).isEqualTo(result);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
    }

    @Test
    void concurrentFrozenBalanceHasOneWinnerAndOneStaleConflict() throws Exception {
        var first = session("ADMIN", true);
        var second = renew(first);
        var before = OfflineSyncHttpSupport.snapshot(port);
        var input = payload(balance(before), "1.00");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var left = executor.submit(() -> { ready.countDown(); if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); return sync(first, a, input); });
            var right = executor.submit(() -> { ready.countDown(); if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException(); return sync(second, b, input); });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            var x = left.get(30, TimeUnit.SECONDS);
            var y = right.get(30, TimeUnit.SECONDS);
            assertThat(java.util.List.of(x.path("state").asText(), y.path("state").asText())).containsExactlyInAnyOrder("CONFIRMED", "CONFLICT");
            var winner = x.path("state").asText().equals("CONFIRMED") ? x : y;
            var loser = winner == x ? y : x;
            conflict(loser, "CONFLICT", "STALE_CASHFLOW_BALANCE");
            confirmed(winner, UUID.fromString(winner.path("operationId").asText()), before, "1.00");
        }
    }

    @Test
    void legacyExpenseInsertWaitsForOfflineLedgerLockAndRetainsDelegatedCashier() throws Exception {
        var session = session("ADMIN", true);
        var delegated = OfflineSyncHttpSupport.createUser(port, "CAJERO");
        var before = OfflineSyncHttpSupport.snapshot(port);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var legacyEntered = new CountDownLatch(1);
        var delegatedCommand = new AtomicReference<RegistrarEgresoCommand>();
        doAnswer(call -> {
            RegistrarEgresoCommand command = call.getArgument(0);
            if (command.categoria().equals("Offline supplies")) {
                entered.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout");
            } else { delegatedCommand.set(command); legacyEntered.countDown(); }
            return call.callRealMethod();
        }).when(expenses).ejecutar(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            UUID op = UUID.randomUUID();
            var offline = executor.submit(() -> sync(session, op, payload(balance(before), "1.00")));
            try {
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                var online = executor.submit(() -> legacy(delegated.id(), "2.00"));
                assertThat(legacyEntered.await(5, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> online.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                var result = offline.get(15, TimeUnit.SECONDS);
                var old = online.get(15, TimeUnit.SECONDS);
                assertThat(result.path("state").asText()).isEqualTo("CONFIRMED");
                assertThat(old.path("tipoMovimiento").asText()).isEqualTo("EGRESO");
                assertThat(new BigDecimal(old.path("montoBs").asText())).isEqualByComparingTo("2.00");
                assertThat(delegatedCommand.get().usuarioCajeroId()).isEqualTo(UUID.fromString(delegated.id()));
                assertThat(balance(OfflineSyncHttpSupport.snapshot(port))).isEqualByComparingTo(balance(before).subtract(new BigDecimal("3.00")));
            } finally { release.countDown(); }
        }
    }

    @Test
    void legacyCashHoldingOrderWaitsAtMovementInsertWithoutDeadlock() throws Exception {
        var session = session("ADMIN", true);
        var delegated = OfflineSyncHttpSupport.createUser(port, "CAJERO");
        var patient = old("mutation($input:CrearPacienteInput!){crearPaciente(input:$input){id}}", Map.of("input", Map.of(
                "ciNit", UUID.randomUUID().toString().replace("-", "").substring(0, 24), "nombres", "Cash",
                "apellidos", "Patient", "telefonoWhatsapp", "+59170012345"))).path("crearPaciente");
        var service = old("mutation($input:CrearServicioInput!){crearServicioOdontologico(input:$input){id}}", Map.of("input", Map.of(
                "codigo", "lock-" + UUID.randomUUID().toString().substring(0, 18), "nombre", "Cash test",
                "descripcion", "Synthetic", "precioBaseBs", "120.50"))).path("crearServicioOdontologico");
        var order = old("mutation($input:CrearOrdenCobroInput!){crearOrdenCobroManual(input:$input){id cuotas{id}}}",
                Map.of("input", Map.of("pacienteId", patient.path("id").asText(), "items", java.util.List.of(
                        Map.of("servicioId", service.path("id").asText(), "cantidad", 1))))).path("crearOrdenCobroManual");
        var before = OfflineSyncHttpSupport.snapshot(port);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var inserting = new CountDownLatch(1);
        doAnswer(call -> { entered.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Barrier timeout"); return call.callRealMethod(); })
                .when(expenses).ejecutar(any());
        doAnswer(call -> {
            com.dentalplatform.erp.financebilling.domain.model.MovimientoCaja movement = call.getArgument(0);
            if (movement.tipoMovimiento().name().equals("INGRESO")) inserting.countDown();
            return call.callRealMethod();
        }).when(movements).guardar(any());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            UUID op = UUID.randomUUID();
            var offline = executor.submit(() -> sync(session, op, payload(balance(before), "1.00")));
            try {
                assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
                var online = executor.submit(() -> old("mutation($input:CobrarEfectivoInput!){cobrarCuotaEnEfectivo(input:$input){id}}",
                        Map.of("input", Map.of("cuotaId", order.path("cuotas").get(0).path("id").asText(),
                                "usuarioCajeroId", delegated.id(), "ciNitCliente", "1234567 SC", "razonSocialCliente", "Synthetic Customer"))));
                assertThat(inserting.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> online.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
                release.countDown();
                assertThat(offline.get(20, TimeUnit.SECONDS).path("state").asText()).isEqualTo("CONFIRMED");
                assertThat(UUID.fromString(online.get(20, TimeUnit.SECONDS).path("cobrarCuotaEnEfectivo").path("id").asText())).isNotNull();
                var after = OfflineSyncHttpSupport.snapshot(port);
                assertThat(balance(after)).isEqualByComparingTo(balance(before).add(new BigDecimal("119.50")));
                assertThat(after.path("obtenerFlujoCaja").path("movimientos").size()).isEqualTo(before.path("obtenerFlujoCaja").path("movimientos").size() + 2);
            } finally { release.countDown(); }
        }
    }

    private JsonNode old(String query, Map<String, Object> args) throws Exception {
        var response = OfflineSyncHttpSupport.send(port, query, args, OfflineSyncHttpSupport.admin(), UUID.randomUUID().toString(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = OfflineSyncHttpSupport.json(response.body());
        assertThat(body.has("errors")).as(response.body()).isFalse();
        return body.path("data");
    }

    @Test
    void failureAfterConfirmationSqlRollsBackMovementAndAuditThenRetrySucceeds() throws Exception {
        var session = session("ADMIN", true);
        var before = OfflineSyncHttpSupport.snapshot(port);
        var input = payload(balance(before), "1.00");
        UUID op = UUID.randomUUID();
        var once = new AtomicBoolean(true);
        doAnswer(call -> {
            @SuppressWarnings("unchecked") Optional<OfflineOperationReceipt> result = (Optional<OfflineOperationReceipt>) call.callRealMethod();
            if (result.isPresent() && result.get().state() == OfflineOperationReceipt.State.CONFIRMED
                    && result.get().type() == OfflineOperationReceipt.Type.CASH_EXPENSE && once.getAndSet(false)) {
                throw new TransientDataAccessResourceException("Synthetic after expense confirmation SQL");
            }
            return result;
        }).when(operations).receipt(any(UUID.class), any(UUID.class));
        var failed = sync(session, op, input);
        conflict(failed, "PREPARED", "TECHNICAL_RETRY_REQUIRED");
        assertThat(status(session, op)).isEqualTo(failed);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(before);
        var recovered = sync(session, op, input);
        assertThat(recovered.path("payloadHash").asText()).isEqualTo(failed.path("payloadHash").asText());
        confirmed(recovered, op, before, "1.00");
        var after = OfflineSyncHttpSupport.snapshot(port);
        assertThat(sync(session, op, input)).isEqualTo(recovered);
        assertThat(OfflineSyncHttpSupport.snapshot(port)).isEqualTo(after);
    }
}
