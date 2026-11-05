package com.dentalplatform.erp.offline;

import com.dentalplatform.erp.financebilling.application.port.ReceiptPdfGeneratorPort;
import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.shared.infrastructure.idempotency.IdempotencyFilter;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Replays the mutations the frontend stores offline (cobrarCuotaEnEfectivo, registrarEgresoCaja,
 * crearOrdenCobroManual, crearPaciente) through the real servlet chain: Spring Security, IdempotencyFilter
 * and asynchronous GraphQL dispatch, on a real PostgreSQL. Every fixture is created here with unique values.
 */
class OfflineReplayHttpIntegrationTest extends IntegrationTestBase {

    private static final String CASHIER_ROLE = "10000000-0000-0000-0000-000000000002";
    private static final String IN_PROGRESS = "{\"error\":\"Ya existe una solicitud en curso con esta Idempotency-Key\"}";
    private static final String MISMATCH = "{\"error\":\"La Idempotency-Key ya fue usada con una solicitud diferente\"}";
    private static final String NOT_PENDING = "La cuota no está pendiente de pago";

    private static final String PAY = """
            mutation($input: CobrarEfectivoInput!) { cobrarCuotaEnEfectivo(input: $input) {
              id numeroRecibo montoTotalBs ciNitCliente razonSocialCliente concepto } }""";
    private static final String EXPENSE = """
            mutation($input: RegistrarEgresoInput!) { registrarEgresoCaja(input: $input) {
              id tipoMovimiento categoria metodoPago montoBs descripcion } }""";
    private static final String CREATE_ORDER = """
            mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) {
              id montoSubtotalBs montoTotalBs saldoPendienteBs estado cuotas { id numeroCuota montoCuotaBs estado } } }""";
    private static final String PLAN = """
            mutation($input: ConfigurarPlanPagosInput!) { configurarPlanDePagos(input: $input) {
              id modalidadPago montoSubtotalBs descuentoBs montoTotalBs saldoPendienteBs estado cantidadCuotas
              cuotas { id numeroCuota montoCuotaBs fechaVencimiento estado } } }""";
    private static final String CREATE_PATIENT = """
            mutation($input: CrearPacienteInput!) { crearPaciente(input: $input) {
              id ciNit nombres apellidos telefonoWhatsapp } }""";

    @Autowired private WebApplicationContext context;
    @Autowired private FilterRegistrationBean<IdempotencyFilter> idempotencyFilter;
    @Autowired private JsonMapper json;
    @MockitoBean private ReceiptPdfGeneratorPort pdf;
    @MockitoBean private ReceiptStoragePort storage;

    private MockMvc mvc;

    @BeforeEach
    void setUpMvc() {
        mvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity())
                .addFilter(idempotencyFilter.getFilter(), "/graphql").build();
        when(pdf.generar(any())).thenReturn(new byte[]{1});
        when(storage.referencia(any())).thenReturn("file:///offline-replay/receipt.pdf");
        when(storage.guardar(any(), any())).thenReturn("file:///offline-replay/receipt.pdf");
    }

    // ---------------------------------------------------------------- 1. sequential replay

    @Test
    void replayedCobroExecutesOnceAndReturnsTheIdenticalBody() throws Exception {
        UUID cashier = newCashier();
        Order order = createOrder(newService("1000.00"), 1);
        var before = snapshot();

        Resp first = send(unique("cobro"), PAY, payInput(order.cuotas.get(0), cashier));
        String key = first.key;
        Resp second = send(key, PAY, payInput(order.cuotas.get(0), cashier));
        Resp third = send(key, PAY, payInput(order.cuotas.get(0), cashier));

        assertThat(first.status).isEqualTo(200);
        assertThat(first.replayed).isNull();
        assertThat(first.body).doesNotContain("\"errors\"");
        for (Resp replay : List.of(second, third)) {
            assertThat(replay.status).isEqualTo(200);
            assertThat(replay.replayed).isEqualTo("true");
            assertThat(replay.body).isEqualTo(first.body);
        }
        assertThat(diff(before, snapshot())).isEqualTo(Map.ofEntries(
                Map.entry("cuotas.PENDIENTE", d("-1")), Map.entry("cuotas.PAGADA", d("1")),
                Map.entry("ordenes.PENDIENTE", d("-1")), Map.entry("ordenes.PAGADO", d("1")),
                Map.entry("saldoSum", d("-1000")), Map.entry("movimientos", d("1")),
                Map.entry("ingresosSum", d("1000")), Map.entry("recibos", d("1")), Map.entry("reciboSeq", d("1")),
                // CUOTA_COBRADA plus the receipt PDF ownership reservation, each exactly once.
                Map.entry("audit", d("2")), Map.entry("idempotency", d("1"))));
        assertThat(first.data("cobrarCuotaEnEfectivo").path("numeroRecibo").asString()).matches("REC-\\d{4}-\\d{6,}");
    }

    @Test
    void replayedEgresoExecutesOnceAndReturnsTheIdenticalBody() throws Exception {
        UUID cashier = newCashier();
        var before = snapshot();
        Map<String, Object> input = egressInput(cashier, "45.50");

        Resp first = send(unique("egreso"), EXPENSE, input);
        Resp second = send(first.key, EXPENSE, input);
        Resp third = send(first.key, EXPENSE, input);

        assertThat(first.status).isEqualTo(200);
        assertThat(first.replayed).isNull();
        assertThat(first.body).doesNotContain("\"errors\"");
        assertThat(first.data("registrarEgresoCaja").path("tipoMovimiento").asString()).isEqualTo("EGRESO");
        for (Resp replay : List.of(second, third)) {
            assertThat(replay.status).isEqualTo(200);
            assertThat(replay.replayed).isEqualTo("true");
            assertThat(replay.body).isEqualTo(first.body);
        }
        assertThat(diff(before, snapshot())).isEqualTo(Map.of("movimientos", d("1"), "egresosSum", d("45.50"),
                "audit", d("1"), "idempotency", d("1")));
    }

    @Test
    void replayedOrderCreationExecutesOnceAndReturnsTheIdenticalBody() throws Exception {
        UUID service = newService("250.00");
        Map<String, Object> input = orderInput(service, 2);
        var before = snapshot();

        Resp first = send(unique("orden"), CREATE_ORDER, input);
        Resp second = send(first.key, CREATE_ORDER, input);
        Resp third = send(first.key, CREATE_ORDER, input);

        assertThat(first.status).isEqualTo(200);
        assertThat(first.replayed).isNull();
        assertThat(first.body).doesNotContain("\"errors\"");
        for (Resp replay : List.of(second, third)) {
            assertThat(replay.replayed).isEqualTo("true");
            assertThat(replay.body).isEqualTo(first.body);
        }
        assertThat(diff(before, snapshot())).isEqualTo(Map.of("ordenes", d("1"), "ordenes.PENDIENTE", d("1"),
                "cuotas", d("1"), "cuotas.PENDIENTE", d("1"), "saldoSum", d("500"),
                "audit", d("1"), "idempotency", d("1")));
    }

    @Test
    void replayedPatientCreationExecutesOnceAndReturnsTheIdenticalBody() throws Exception {
        Map<String, Object> input = patientInput(uniqueCi());
        var before = snapshot();

        Resp first = send(unique("paciente"), CREATE_PATIENT, input);
        Resp second = send(first.key, CREATE_PATIENT, input);
        Resp third = send(first.key, CREATE_PATIENT, input);

        assertThat(first.status).isEqualTo(200);
        assertThat(first.replayed).isNull();
        assertThat(first.body).doesNotContain("\"errors\"");
        for (Resp replay : List.of(second, third)) {
            assertThat(replay.replayed).isEqualTo("true");
            assertThat(replay.body).isEqualTo(first.body);
        }
        assertThat(diff(before, snapshot())).isEqualTo(Map.of("pacientes", d("1"), "audit", d("1"),
                "idempotency", d("1")));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pacientes WHERE ci_nit = ?", Integer.class,
                input.get("ciNit"))).isEqualTo(1);
    }

    // ---------------------------------------------------------------- 2. concurrency

    @Test
    void concurrentCobroWithTheSameKeyExecutesExactlyOnce() throws Exception {
        UUID cashier = newCashier();
        Order order = createOrder(newService("1000.00"), 1);
        var before = snapshot();
        String key = unique("same-key");

        List<Resp> results = concurrently(6, () -> send(key, PAY, payInput(order.cuotas.get(0), cashier)));

        List<Resp> executed = results.stream().filter(r -> r.status == 200 && r.replayed == null).toList();
        assertThat(executed).as("exactly one request executes the mutation").hasSize(1);
        assertThat(executed.get(0).body).doesNotContain("\"errors\"");
        for (Resp other : results) {
            if (other == executed.get(0)) continue;
            if (other.status == 409) {
                assertThat(other.body).isEqualTo(IN_PROGRESS);
            } else {
                assertThat(other.status).isEqualTo(200);
                assertThat(other.replayed).isEqualTo("true");
                assertThat(other.body).isEqualTo(executed.get(0).body);
            }
        }
        assertThat(diff(before, snapshot())).isEqualTo(singlePaymentDelta("1000", "PENDIENTE", "ordenes.PAGADO"));
    }

    @Test
    void concurrentCobroWithDifferentKeysYieldsOneSuccessAndTheRestRejected() throws Exception {
        UUID cashier = newCashier();
        Order order = createOrder(newService("1000.00"), 1);
        var before = snapshot();

        List<Resp> results = concurrently(6, () -> send(unique("diff-key"), PAY, payInput(order.cuotas.get(0), cashier)));

        List<Resp> successes = results.stream().filter(r -> r.status == 200 && !r.body.contains("\"errors\"")).toList();
        assertThat(successes).as("exactly one payment succeeds").hasSize(1);
        for (Resp rejected : results) {
            if (rejected == successes.get(0)) continue;
            assertThat(rejected.status).isEqualTo(200);
            assertThat(rejected.replayed).isNull();
            assertRejected(rejected, NOT_PENDING);
        }
        // Rejected keys are released; only the winning key stays stored.
        assertThat(diff(before, snapshot())).isEqualTo(singlePaymentDelta("1000", "PENDIENTE", "ordenes.PAGADO"));
    }

    // ---------------------------------------------------------------- 3. already paid, new key

    @Test
    void alreadyPaidCuotaChargedAgainWithANewKeyIsRejectedWithoutAnyChange() throws Exception {
        UUID cashier = newCashier();
        Order order = createOrder(newService("300.00"), 1);
        Resp paid = send(unique("first"), PAY, payInput(order.cuotas.get(0), cashier));
        assertThat(paid.body).doesNotContain("\"errors\"");
        var before = snapshot();

        Resp again = send(unique("second"), PAY, payInput(order.cuotas.get(0), cashier));

        assertThat(again.status).isEqualTo(200);
        assertThat(again.replayed).isNull();
        assertRejected(again, NOT_PENDING);
        assertThat(snapshot()).as("rows, sums, receipt sequence, audit and idempotency keys unchanged").isEqualTo(before);
    }

    // ---------------------------------------------------------------- 4. key reuse, different payload

    @Test
    void sameKeyWithADifferentPayloadIsRejectedWith422AndNothingExecutes() throws Exception {
        UUID cashier = newCashier();
        Resp first = send(unique("mismatch"), EXPENSE, egressInput(cashier, "10.00"));
        assertThat(first.body).doesNotContain("\"errors\"");
        var before = snapshot();

        Resp other = send(first.key, EXPENSE, egressInput(cashier, "11.00"));

        assertThat(other.status).isEqualTo(422);
        assertThat(other.body).isEqualTo(MISMATCH);
        assertThat(other.replayed).isNull();
        assertThat(snapshot()).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT response_payload::text FROM idempotency_keys WHERE idempotency_key = ?",
                String.class, first.key)).contains("registrarEgresoCaja");
    }

    // ---------------------------------------------------------------- 5. rejection leaves nothing, key released

    @Test
    void rejectedEgresoLeavesNoChangesAndTheKeyIsReleasedForACorrectedRetry() throws Exception {
        UUID cashier = newCashier();
        UUID unknown = UUID.randomUUID();
        String key = unique("retry");
        var before = snapshot();

        Resp bad = send(key, EXPENSE, egressInput(unknown, "20.00"));
        Resp badAgain = send(key, EXPENSE, egressInput(unknown, "20.00"));

        for (Resp rejected : List.of(bad, badAgain)) {
            assertThat(rejected.status).isEqualTo(200);
            assertThat(rejected.replayed).as("an error is never replayed").isNull();
            assertRejected(rejected, "El usuario cajero no existe: " + unknown);
        }
        assertThat(snapshot()).as("no partial change; GraphQL errors release the key").isEqualTo(before);

        Resp fixed = send(key, EXPENSE, egressInput(cashier, "20.00"));

        assertThat(fixed.status).isEqualTo(200);
        assertThat(fixed.replayed).isNull();
        assertThat(fixed.body).doesNotContain("\"errors\"");
        assertThat(diff(before, snapshot())).isEqualTo(Map.of("movimientos", d("1"), "egresosSum", d("20"),
                "audit", d("1"), "idempotency", d("1")));
    }

    @Test
    void rejectedCobrosLeaveNoFinancialChangesNorConsumeReceiptNumbers() throws Exception {
        UUID cashier = newCashier();
        Order order = createOrder(newService("500.00"), 1);
        var before = snapshot();

        Resp unknownCashier = send(unique("cobro-bad-cajero"), PAY, payInput(order.cuotas.get(0), UUID.randomUUID()));
        Resp unknownQuota = send(unique("cobro-bad-cuota"), PAY, payInput(UUID.randomUUID(), cashier));

        assertThat(unknownCashier.status).isEqualTo(200);
        assertThat(unknownCashier.body).contains("\"classification\":\"BAD_REQUEST\"").contains("El usuario cajero no existe: ");
        assertThat(unknownQuota.status).isEqualTo(200);
        assertThat(unknownQuota.body).contains("\"classification\":\"NOT_FOUND\"").contains("La cuota no existe");
        assertThat(snapshot()).isEqualTo(before);

        Resp valid = send(unknownQuota.key, PAY, payInput(order.cuotas.get(0), cashier));
        assertThat(valid.replayed).isNull();
        assertThat(valid.body).doesNotContain("\"errors\"");
        assertThat(diff(before, snapshot())).isEqualTo(singlePaymentDelta("500", "PENDIENTE", "ordenes.PAGADO"));
    }

    @Test
    void graphQlValidationErrorReleasesTheKeyToo() throws Exception {
        String key = unique("invalid");
        var before = snapshot();

        Resp invalid = send(key, PAY, Map.of("cuotaId", UUID.randomUUID().toString()));

        assertThat(invalid.status).isEqualTo(200);
        assertThat(invalid.body).contains("\"errors\"").doesNotContain("\"cobrarCuotaEnEfectivo\":{");
        assertThat(snapshot()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- 6. plans

    @Test
    void creditPlansKeepTheExactTotalAndPutRemainderCentsOnTheLastCuota() throws Exception {
        for (String price : List.of("1000.00", "100.01")) {
            for (int parts : List.of(3, 7)) {
                Order order = createOrder(newService(price), 1);
                var before = snapshot();

                Resp plan = send(unique("plan"), PLAN, planInput(order.id, parts, "0.00"));

                assertThat(plan.status).isEqualTo(200);
                assertThat(plan.body).doesNotContain("\"errors\"");
                assertPlanConsistent(order.id, new BigDecimal(price), BigDecimal.ZERO, parts, plan);
                assertThat(diff(before, snapshot()).keySet()).doesNotContain("movimientos", "recibos", "reciboSeq");
            }
        }
        // Literal cases from the requirement, independent from the helper's formula.
        assertThat(cuotaAmounts(createdPlan("1000.00", 3, "0.00"))).containsExactly("333.33", "333.33", "333.34");
        assertThat(cuotaAmounts(createdPlan("100.01", 7, "0.00")))
                .containsExactly("14.28", "14.28", "14.28", "14.28", "14.28", "14.28", "14.33");
    }

    @Test
    void discountKeepsTotalEqualToSubtotalMinusDiscount() throws Exception {
        UUID orderId = createdPlan("1000.00", 7, "100.01");
        assertThat(jdbc.queryForObject("SELECT monto_total_bs FROM ordenes_cobro WHERE id = ?", BigDecimal.class, orderId))
                .isEqualByComparingTo("899.99");
        assertThat(jdbc.queryForObject("SELECT sum(monto_cuota_bs) FROM cuotas_pago WHERE orden_cobro_id = ?",
                BigDecimal.class, orderId)).isEqualByComparingTo("899.99");

        Order small = createOrder(newService("100.01"), 1);
        Resp plan = send(unique("plan-discount"), PLAN, planInput(small.id, 3, "0.01"));
        assertThat(plan.body).doesNotContain("\"errors\"");
        assertPlanConsistent(small.id, new BigDecimal("100.01"), new BigDecimal("0.01"), 3, plan);

        Order rejectedOrder = createOrder(newService("50.00"), 1);
        var before = snapshot();
        Resp tooBig = send(unique("plan-too-big"), PLAN, planInput(rejectedOrder.id, 3, "50.01"));
        assertRejected(tooBig, "El descuento no puede superar el subtotal");
        assertThat(snapshot()).isEqualTo(before);
    }

    // ---------------------------------------------------------------- 7. confirmed cobro consistency

    @Test
    void confirmedCobroUpdatesCuotaOrderMovementAndReceiptConsistently() throws Exception {
        UUID cashier = newCashier();
        Order order = createOrder(newService("900.00"), 1);
        assertThat(send(unique("plan-7"), PLAN, planInput(order.id, 3, "0.00")).body).doesNotContain("\"errors\"");
        List<UUID> quotas = jdbc.queryForList("SELECT id FROM cuotas_pago WHERE orden_cobro_id = ? ORDER BY numero_cuota",
                UUID.class, order.id);
        assertThat(quotas).hasSize(3);
        long seqBefore = snapshot().get("reciboSeq").longValueExact();

        for (int i = 0; i < 3; i++) {
            UUID quota = quotas.get(i);
            Resp paid = send(unique("cobro-7"), PAY, payInput(quota, cashier));
            assertThat(paid.status).isEqualTo(200);
            assertThat(paid.body).doesNotContain("\"errors\"");
            String receiptId = paid.data("cobrarCuotaEnEfectivo").path("id").asString();

            assertThat(jdbc.queryForObject("SELECT estado FROM cuotas_pago WHERE id = ?", String.class, quota))
                    .isEqualTo("PAGADA");
            assertThat(jdbc.queryForObject("SELECT fecha_pago IS NOT NULL FROM cuotas_pago WHERE id = ?", Boolean.class, quota))
                    .isTrue();
            assertThat(jdbc.queryForObject("SELECT saldo_pendiente_bs FROM ordenes_cobro WHERE id = ?", BigDecimal.class,
                    order.id)).isEqualByComparingTo(String.valueOf(300 * (2 - i)));
            assertThat(jdbc.queryForObject("SELECT estado FROM ordenes_cobro WHERE id = ?", String.class, order.id))
                    .isEqualTo(i == 2 ? "PAGADO" : "PARCIAL");
            Map<String, Object> movement = jdbc.queryForMap(
                    "SELECT id, tipo_movimiento, metodo_pago, categoria, monto_bs, usuario_cajero_id FROM movimientos_caja WHERE cuota_id = ?",
                    quota);
            assertThat(movement.get("tipo_movimiento")).isEqualTo("INGRESO");
            assertThat(movement.get("metodo_pago")).isEqualTo("EFECTIVO");
            assertThat(movement.get("categoria")).isEqualTo("COBRO_CUOTA");
            assertThat((BigDecimal) movement.get("monto_bs")).isEqualByComparingTo("300");
            assertThat(movement.get("usuario_cajero_id")).isEqualTo(cashier);
            Map<String, Object> receipt = jdbc.queryForMap(
                    "SELECT id, movimiento_caja_id, monto_total_bs, ci_nit_cliente FROM recibos WHERE movimiento_caja_id = ?",
                    movement.get("id"));
            assertThat(receipt.get("id").toString()).isEqualTo(receiptId);
            assertThat((BigDecimal) receipt.get("monto_total_bs")).isEqualByComparingTo("300");
            assertThat(receipt.get("ci_nit_cliente")).isEqualTo("1234567 SC");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM movimientos_caja WHERE cuota_id IN (SELECT id FROM cuotas_pago WHERE orden_cobro_id = ?)",
                Integer.class, order.id)).isEqualTo(3);
        assertThat(snapshot().get("reciboSeq").longValueExact()).isEqualTo(seqBefore + 3);
    }

    // ---------------------------------------------------------------- helpers: HTTP

    private record Resp(String key, int status, String body, String replayed, JsonMapper mapper) {
        JsonNode data(String field) {
            return mapper.readTree(body).path("data").path(field);
        }
    }

    private Resp send(String key, String query, Map<String, Object> input) throws Exception {
        String payload = json.writeValueAsString(Map.of("query", query, "variables", Map.of("input", input)));
        MvcResult result = mvc.perform(post("/graphql").contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON).header(IdempotencyFilter.HEADER, key).content(payload)).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(asyncDispatch(result)).andReturn();
        }
        var response = result.getResponse();
        return new Resp(key, response.getStatus(), response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8),
                response.getHeader(IdempotencyFilter.REPLAYED_HEADER), json);
    }

    private <T> List<T> concurrently(int n, Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        try {
            CountDownLatch ready = new CountDownLatch(n);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            ready.await();
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> f : futures) results.add(f.get());
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertRejected(Resp response, String message) {
        JsonNode error = json.readTree(response.body).path("errors").path(0);
        assertThat(error.path("message").asString()).isEqualTo(message);
        assertThat(error.path("extensions").path("classification").asString()).isEqualTo("BAD_REQUEST");
    }

    // ---------------------------------------------------------------- helpers: inputs and fixtures

    private static String unique(String prefix) {
        return "offline-" + prefix + "-" + UUID.randomUUID();
    }

    private static String uniqueCi() {
        return String.valueOf(Math.abs(System.nanoTime() % 90_000_000L) + 10_000_000L) + "-OFF";
    }

    private Map<String, Object> payInput(UUID quota, UUID cashier) {
        return Map.of("cuotaId", quota.toString(), "usuarioCajeroId", cashier.toString(),
                "ciNitCliente", "1234567 SC", "razonSocialCliente", "Paciente Sintético Offline");
    }

    private Map<String, Object> egressInput(UUID cashier, String amount) {
        return Map.of("usuarioCajeroId", cashier.toString(), "categoria", "INSUMOS", "metodoPago", "EFECTIVO",
                "montoBs", new BigDecimal(amount), "descripcion", "Compra de insumos offline");
    }

    private Map<String, Object> orderInput(UUID service, int quantity) {
        return Map.of("pacienteId", newPatient().toString(), "items",
                List.of(Map.of("servicioId", service.toString(), "cantidad", quantity)));
    }

    private Map<String, Object> patientInput(String ci) {
        return Map.of("ciNit", ci, "nombres", "Marcela", "apellidos", "Rojas Vaca", "telefonoWhatsapp", "+59170012345");
    }

    private Map<String, Object> planInput(UUID orderId, int parts, String discount) {
        return Map.of("ordenCobroId", orderId.toString(), "modalidadPago", "CREDITO", "descuentoBs", new BigDecimal(discount),
                "cantidadCuotas", parts, "fechaPrimeraCuota", "2030-01-15", "intervaloDias", 30);
    }

    private UUID newCashier() {
        UUID id = UUID.randomUUID();
        String suffix = id.toString().substring(0, 12);
        jdbc.update("INSERT INTO usuarios (id, rol_id, username, email, nombre_completo) VALUES (?, ?::uuid, ?, ?, ?)",
                id, CASHIER_ROLE, "offline-" + suffix, "offline-" + suffix + "@example.test", "Cajero Offline " + suffix);
        return id;
    }

    private UUID newPatient() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pacientes (id, ci_nit, nombres, apellidos, telefono_whatsapp) VALUES (?, ?, 'Paciente', 'Offline', '+59170000000')",
                id, "OFF-" + id.toString().substring(0, 18));
        return id;
    }

    private UUID newService(String price) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO servicios_odontologicos (id, codigo, nombre, precio_base_bs) VALUES (?, ?, 'Servicio offline', ?)",
                id, "OFF-" + id.toString().substring(0, 20), new BigDecimal(price));
        return id;
    }

    private record Order(UUID id, List<UUID> cuotas) {
    }

    private Order createOrder(UUID service, int quantity) throws Exception {
        Resp created = send(unique("fixture-order"), CREATE_ORDER, orderInput(service, quantity));
        assertThat(created.body).doesNotContain("\"errors\"");
        UUID id = UUID.fromString(created.data("crearOrdenCobroManual").path("id").asString());
        return new Order(id, jdbc.queryForList("SELECT id FROM cuotas_pago WHERE orden_cobro_id = ? ORDER BY numero_cuota",
                UUID.class, id));
    }

    private UUID createdPlan(String price, int parts, String discount) throws Exception {
        Order order = createOrder(newService(price), 1);
        Resp plan = send(unique("plan-fixture"), PLAN, planInput(order.id, parts, discount));
        assertThat(plan.body).doesNotContain("\"errors\"");
        return order.id;
    }

    private List<String> cuotaAmounts(UUID orderId) {
        return jdbc.queryForList("SELECT monto_cuota_bs FROM cuotas_pago WHERE orden_cobro_id = ? ORDER BY numero_cuota",
                BigDecimal.class, orderId).stream().map(BigDecimal::toPlainString).toList();
    }

    private void assertPlanConsistent(UUID orderId, BigDecimal subtotal, BigDecimal discount, int parts, Resp plan) {
        BigDecimal total = subtotal.subtract(discount);
        BigDecimal base = total.divide(BigDecimal.valueOf(parts), 2, RoundingMode.DOWN);
        BigDecimal last = total.subtract(base.multiply(BigDecimal.valueOf(parts - 1)));
        List<BigDecimal> amounts = jdbc.queryForList(
                "SELECT monto_cuota_bs FROM cuotas_pago WHERE orden_cobro_id = ? ORDER BY numero_cuota", BigDecimal.class, orderId);
        assertThat(amounts).hasSize(parts);
        assertThat(amounts.subList(0, parts - 1)).allSatisfy(a -> assertThat(a).isEqualByComparingTo(base));
        assertThat(amounts.get(parts - 1)).isEqualByComparingTo(last);
        assertThat(amounts.stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(total);
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT monto_subtotal_bs, descuento_bs, monto_total_bs, saldo_pendiente_bs, cantidad_cuotas, estado, modalidad_pago FROM ordenes_cobro WHERE id = ?",
                orderId);
        assertThat((BigDecimal) row.get("monto_subtotal_bs")).isEqualByComparingTo(subtotal);
        assertThat((BigDecimal) row.get("descuento_bs")).isEqualByComparingTo(discount);
        assertThat((BigDecimal) row.get("monto_total_bs")).isEqualByComparingTo(total);
        assertThat((BigDecimal) row.get("saldo_pendiente_bs")).isEqualByComparingTo(total);
        assertThat(row.get("cantidad_cuotas")).isEqualTo(parts);
        assertThat(row.get("estado")).isEqualTo("PENDIENTE");
        assertThat(row.get("modalidad_pago")).isEqualTo("CREDITO");
        assertThat(jdbc.queryForList("SELECT fecha_vencimiento FROM cuotas_pago WHERE orden_cobro_id = ? ORDER BY numero_cuota",
                LocalDate.class, orderId))
                .isEqualTo(java.util.stream.IntStream.range(0, parts)
                        .mapToObj(i -> LocalDate.of(2030, 1, 15).plusDays(30L * i)).toList());
        // The HTTP body mirrors the stored plan.
        List<BigDecimal> bodyAmounts = new ArrayList<>();
        plan.data("configurarPlanDePagos").path("cuotas").forEach(c -> bodyAmounts.add(new BigDecimal(c.path("montoCuotaBs").asString())));
        assertThat(bodyAmounts).usingElementComparator(BigDecimal::compareTo).isEqualTo(amounts);
    }

    // ---------------------------------------------------------------- helpers: financial snapshot

    private static BigDecimal d(String value) {
        return new BigDecimal(value).stripTrailingZeros();
    }

    private Map<String, BigDecimal> singlePaymentDelta(String amount, String fromQuota, String toOrder) {
        return Map.ofEntries(
                Map.entry("cuotas." + fromQuota, d("-1")), Map.entry("cuotas.PAGADA", d("1")),
                Map.entry("ordenes.PENDIENTE", d("-1")), Map.entry(toOrder, d("1")),
                Map.entry("saldoSum", d(amount).negate()), Map.entry("movimientos", d("1")),
                Map.entry("ingresosSum", d(amount)), Map.entry("recibos", d("1")), Map.entry("reciboSeq", d("1")),
                // CUOTA_COBRADA plus the receipt PDF ownership reservation, each exactly once.
                Map.entry("audit", d("2")), Map.entry("idempotency", d("1")));
    }

    /** Counts and sums of every table a replayed mutation could touch, including the receipt sequence. */
    private Map<String, BigDecimal> snapshot() {
        Map<String, BigDecimal> s = new LinkedHashMap<>();
        for (String state : List.of("PENDIENTE", "PAGADA", "VENCIDA", "ANULADA")) {
            s.put("cuotas." + state, num("SELECT count(*) FROM cuotas_pago WHERE estado = '" + state + "'"));
        }
        for (String state : List.of("PENDIENTE", "PARCIAL", "PAGADO", "ANULADO")) {
            s.put("ordenes." + state, num("SELECT count(*) FROM ordenes_cobro WHERE estado = '" + state + "'"));
        }
        s.put("cuotas", num("SELECT count(*) FROM cuotas_pago"));
        s.put("ordenes", num("SELECT count(*) FROM ordenes_cobro"));
        s.put("saldoSum", num("SELECT coalesce(sum(saldo_pendiente_bs), 0) FROM ordenes_cobro"));
        s.put("movimientos", num("SELECT count(*) FROM movimientos_caja"));
        s.put("ingresosSum", num("SELECT coalesce(sum(monto_bs), 0) FROM movimientos_caja WHERE tipo_movimiento = 'INGRESO'"));
        s.put("egresosSum", num("SELECT coalesce(sum(monto_bs), 0) FROM movimientos_caja WHERE tipo_movimiento = 'EGRESO'"));
        s.put("recibos", num("SELECT count(*) FROM recibos"));
        s.put("reciboSeq", num("SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END FROM recibo_numero_seq"));
        s.put("audit", num("SELECT count(*) FROM audit_logs"));
        s.put("idempotency", num("SELECT count(*) FROM idempotency_keys"));
        s.put("pacientes", num("SELECT count(*) FROM pacientes"));
        return s;
    }

    private BigDecimal num(String sql) {
        return jdbc.queryForObject(sql, BigDecimal.class).stripTrailingZeros();
    }

    private static Map<String, BigDecimal> diff(Map<String, BigDecimal> before, Map<String, BigDecimal> after) {
        Map<String, BigDecimal> changes = new LinkedHashMap<>();
        after.forEach((k, v) -> {
            BigDecimal delta = v.subtract(before.get(k));
            if (delta.signum() != 0) changes.put(k, delta.stripTrailingZeros());
        });
        return changes;
    }
}
