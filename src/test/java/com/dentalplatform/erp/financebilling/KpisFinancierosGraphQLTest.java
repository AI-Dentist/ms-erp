package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.port.ReceiptPdfGeneratorPort;
import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@WithMockUser(roles = "ADMIN")
class KpisFinancierosGraphQLTest extends IntegrationTestBase {

    @MockitoBean
    private ReceiptPdfGeneratorPort pdf;

    @MockitoBean
    private ReceiptStoragePort storage;

    private static final String FULL_QUERY = """
            query($start: DateTime!, $end: DateTime!) {
              obtenerKpisFinancieros(fechaInicio: $start, fechaFin: $end) {
                fechaInicio
                fechaFin
                ingresos {
                  totalBs
                  cantidadMovimientos
                  montoNominalQrBs
                  seriePorDia {
                    fecha
                    montoBs
                  }
                }
                morosidad {
                  cuotasVencidas
                  cuotasExigibles
                  porcentaje
                  montoVencidoBs
                  detalle {
                    cuotaId
                    ordenCobroId
                    pacienteId
                    pacienteNombre
                    numeroCuota
                    montoBs
                    fechaVencimiento
                    diasAtraso
                  }
                }
                serviciosMasSolicitados {
                  servicioId
                  nombre
                  cantidad
                  montoBs
                }
                flujoCaja {
                  totalIngresosBs
                  totalEgresosBs
                  saldoNetoBs
                  seriePorDia {
                    fecha
                    ingresosBs
                    egresosBs
                    saldoBs
                  }
                }
                cobranzaPorMetodo {
                  metodoPago
                  cantidad
                  montoBs
                  porcentajeMonto
                  montoNominal
                }
              }
            }
            """;

    private static final UUID CASHIER_ID = UUID.fromString("20000000-0000-0000-0000-000000000002");

    private UUID createPatient(String prefix) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO pacientes(id, ci_nit, nombres, apellidos, telefono_whatsapp, activo) VALUES (?, ?, ?, ?, ?, ?)",
                id, id.toString().substring(0, 30), prefix + "-Name", prefix + "-LastName", "70000000", true);
        return id;
    }

    private UUID createOrder(UUID patientId, String status, String createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ordenes_cobro(id, paciente_id, monto_subtotal_bs, monto_total_bs, saldo_pendiente_bs, estado, created_at)
                VALUES (?, ?, 100.00, 100.00, 100.00, ?, ?::timestamptz)
                """, id, patientId, status, createdAt);
        return id;
    }

    private void addOrderItem(UUID orderId, UUID serviceId, int qty, double unitPrice) {
        double subtotal = qty * unitPrice;
        jdbc.update("""
                INSERT INTO orden_cobro_items(id, orden_cobro_id, servicio_id, cantidad, precio_unitario_bs, subtotal_bs)
                VALUES (gen_random_uuid(), ?, ?, ?, ?, ?)
                """, orderId, serviceId, qty, unitPrice, subtotal);
    }

    private UUID createQuota(UUID orderId, int num, double amount, String dueDate, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO cuotas_pago(id, orden_cobro_id, numero_cuota, monto_cuota_bs, fecha_vencimiento, estado)
                VALUES (?, ?, ?, ?, ?::date, ?)
                """, id, orderId, num, amount, dueDate, status);
        return id;
    }

    private UUID createMovement(String type, String category, String method, double amount, String timestamp) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO movimientos_caja(id, usuario_cajero_id, tipo_movimiento, categoria, metodo_pago, monto_bs, descripcion, fecha_movimiento)
                VALUES (?, ?, ?, ?, ?, ?, 'Movement synthetic', ?::timestamptz)
                """, id, CASHIER_ID, type, category, method, amount, timestamp);
        return id;
    }

    @Test
    void ingresosKpiRulesAndDailySeriesAggregation() {
        String start = "2040-05-01T00:00:00-04:00";
        String end = "2040-05-10T23:59:59-04:00";

        // Day 2040-05-02: 150 EFECTIVO + 250 QR_PAGOFACIL = 400
        createMovement("INGRESO", "COBRO_CUOTA", "EFECTIVO", 150.00, "2040-05-02T10:00:00-04:00");
        createMovement("INGRESO", "COBRO_CUOTA", "QR_PAGOFACIL", 250.00, "2040-05-02T15:30:00-04:00");

        // Day 2040-05-05: 100 TRANSFERENCIA + 50 EGRESO
        createMovement("INGRESO", "COBRO_CUOTA", "TRANSFERENCIA", 100.00, "2040-05-05T09:00:00-04:00");
        createMovement("EGRESO", "COMPRA_INSUMOS", "EFECTIVO", 50.00, "2040-05-05T11:00:00-04:00");

        // Out of period: 2040-04-30
        createMovement("INGRESO", "COBRO_CUOTA", "EFECTIVO", 200.00, "2040-04-30T23:59:00-04:00");

        var response = graphQl.document(FULL_QUERY)
                .variable("start", start)
                .variable("end", end)
                .execute();

        response.path("obtenerKpisFinancieros.ingresos.totalBs").entity(Double.class).isEqualTo(500.00);
        response.path("obtenerKpisFinancieros.ingresos.cantidadMovimientos").entity(Integer.class).isEqualTo(3);
        response.path("obtenerKpisFinancieros.ingresos.montoNominalQrBs").entity(Double.class).isEqualTo(250.00);

        // Daily series has exactly 2 entries (days without movements are omitted)
        response.path("obtenerKpisFinancieros.ingresos.seriePorDia[0].fecha").entity(String.class).isEqualTo("2040-05-02");
        response.path("obtenerKpisFinancieros.ingresos.seriePorDia[0].montoBs").entity(Double.class).isEqualTo(400.00);
        response.path("obtenerKpisFinancieros.ingresos.seriePorDia[1].fecha").entity(String.class).isEqualTo("2040-05-05");
        response.path("obtenerKpisFinancieros.ingresos.seriePorDia[1].montoBs").entity(Double.class).isEqualTo(100.00);
    }

    @Test
    void morosidadEvaluationDateExigiblesVencidasAnuladaExcludedAndDetalleOrder() {
        // Historical period: fechaFin (2024-03-31) is before now, so evaluation date = 2024-03-31
        String start = "2024-03-01T00:00:00-04:00";
        String end = "2024-03-31T23:59:59-04:00";

        UUID p1 = createPatient("Alice");
        UUID p2 = createPatient("Bob");
        UUID p3 = createPatient("Charlie");
        UUID o1 = createOrder(p1, "PENDIENTE", "2024-02-15T10:00:00-04:00");
        UUID o2 = createOrder(p2, "PENDIENTE", "2024-02-15T10:00:00-04:00");
        UUID o3 = createOrder(p3, "PENDIENTE", "2024-02-15T10:00:00-04:00");

        // Cuota 1: due 2024-03-10, PENDIENTE, 120.00 -> vencida (31 - 10 = 21 dias atraso)
        createQuota(o1, 1, 120.00, "2024-03-10", "PENDIENTE");
        // Cuota 2: due 2024-03-20, VENCIDA, 80.00 -> vencida (31 - 20 = 11 dias atraso)
        createQuota(o2, 1, 80.00, "2024-03-20", "VENCIDA");
        // Cuota 3: due 2024-03-25, PAGADA, 200.00 -> exigible, not vencida
        createQuota(o3, 1, 200.00, "2024-03-25", "PAGADA");
        // Cuota 4: due 2024-03-31, PENDIENTE, 50.00 -> exigible, not vencida (not before 2024-03-31)
        createQuota(o1, 2, 50.00, "2024-03-31", "PENDIENTE");
        // Cuota 5: due 2024-03-15, ANULADA -> excluded
        createQuota(o2, 2, 300.00, "2024-03-15", "ANULADA");
        // Cuota 6: due 2024-04-02, outside the period -> excluded
        createQuota(o3, 2, 70.00, "2024-04-02", "PENDIENTE");

        var response = graphQl.document(FULL_QUERY)
                .variable("start", start)
                .variable("end", end)
                .execute();

        response.path("obtenerKpisFinancieros.morosidad.cuotasExigibles").entity(Integer.class).isEqualTo(4);
        response.path("obtenerKpisFinancieros.morosidad.cuotasVencidas").entity(Integer.class).isEqualTo(2);
        // porcentaje = 2 / 4 * 100 = 50.0
        response.path("obtenerKpisFinancieros.morosidad.porcentaje").entity(Float.class).isEqualTo(50.0f);
        response.path("obtenerKpisFinancieros.morosidad.montoVencidoBs").entity(Double.class).isEqualTo(200.00);

        // Detalle ordered by diasAtraso desc
        response.path("obtenerKpisFinancieros.morosidad.detalle[0].pacienteNombre").entity(String.class).isEqualTo("Alice-Name Alice-LastName");
        response.path("obtenerKpisFinancieros.morosidad.detalle[0].diasAtraso").entity(Integer.class).isEqualTo(21);
        response.path("obtenerKpisFinancieros.morosidad.detalle[0].montoBs").entity(Double.class).isEqualTo(120.00);

        response.path("obtenerKpisFinancieros.morosidad.detalle[1].pacienteNombre").entity(String.class).isEqualTo("Bob-Name Bob-LastName");
        response.path("obtenerKpisFinancieros.morosidad.detalle[1].diasAtraso").entity(Integer.class).isEqualTo(11);
        response.path("obtenerKpisFinancieros.morosidad.detalle[1].montoBs").entity(Double.class).isEqualTo(80.00);
    }

    @Test
    void serviciosMasSolicitadosExcludesAnuladoOrdersAndSortsByQtyThenMonto() {
        String start = "2042-01-01T00:00:00-04:00";
        String end = "2042-01-31T23:59:59-04:00";

        UUID patient = createPatient("ServiceTester");
        UUID s1 = UUID.fromString("70000000-0000-0000-0000-000000000001");
        UUID s2 = UUID.fromString("70000000-0000-0000-0000-000000000002");
        UUID s3 = UUID.fromString("70000000-0000-0000-0000-000000000003");

        // Order 1 (valid): s1 qty 3, s2 qty 1
        UUID o1 = createOrder(patient, "PENDIENTE", "2042-01-05T10:00:00-04:00");
        addOrderItem(o1, s1, 3, 100.00); // 300.00
        addOrderItem(o1, s2, 1, 50.00);  // 50.00

        // Order 2 (valid): s1 qty 2, s3 qty 5
        UUID o2 = createOrder(patient, "PAGADO", "2042-01-10T14:00:00-04:00");
        addOrderItem(o2, s1, 2, 100.00); // 200.00 -> total s1: qty 5, monto 500.00
        addOrderItem(o2, s3, 5, 20.00);  // 100.00 -> total s3: qty 5, monto 100.00

        // Order 3 (ANULADO): must be excluded
        UUID o3 = createOrder(patient, "ANULADO", "2042-01-15T12:00:00-04:00");
        addOrderItem(o3, s2, 10, 500.00);

        // Order 4 (out of period): must be excluded
        UUID o4 = createOrder(patient, "PENDIENTE", "2042-02-05T12:00:00-04:00");
        addOrderItem(o4, s2, 20, 100.00);

        var response = graphQl.document(FULL_QUERY)
                .variable("start", start)
                .variable("end", end)
                .execute();

        // s1: qty 5, monto 500.00
        // s3: qty 5, monto 100.00 (tied on qty, s1 wins on monto)
        // s2: qty 1, monto 50.00
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[0].servicioId").entity(String.class).isEqualTo(s1.toString());
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[0].cantidad").entity(Integer.class).isEqualTo(5);
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[0].montoBs").entity(Double.class).isEqualTo(500.00);

        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[1].servicioId").entity(String.class).isEqualTo(s3.toString());
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[1].cantidad").entity(Integer.class).isEqualTo(5);
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[1].montoBs").entity(Double.class).isEqualTo(100.00);

        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[2].servicioId").entity(String.class).isEqualTo(s2.toString());
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[2].cantidad").entity(Integer.class).isEqualTo(1);
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[2].montoBs").entity(Double.class).isEqualTo(50.00);
    }

    @Test
    void serviciosMasSolicitadosKeepsOnlyTheTopTen() {
        String start = "2043-07-01T00:00:00-04:00";
        String end = "2043-07-31T23:59:59-04:00";
        UUID patient = createPatient("TopTen");
        UUID order = createOrder(patient, "PENDIENTE", "2043-07-10T10:00:00-04:00");
        List<UUID> services = new java.util.ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            UUID service = UUID.randomUUID();
            jdbc.update("INSERT INTO servicios_odontologicos(id, codigo, nombre, precio_base_bs) VALUES (?, ?, ?, 10)",
                    service, "KPI-" + service.toString().substring(0, 20), "Synthetic service " + i);
            addOrderItem(order, service, i, 10.00); // service i has cantidad i
            services.add(service);
        }

        var response = graphQl.document(FULL_QUERY).variable("start", start).variable("end", end).execute();
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados").entityList(Object.class).hasSize(10);
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[0].servicioId").entity(String.class).isEqualTo(services.get(10).toString());
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[0].cantidad").entity(Integer.class).isEqualTo(11);
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[0].nombre").entity(String.class).isEqualTo("Synthetic service 11");
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[9].cantidad").entity(Integer.class).isEqualTo(2);
        response.path("obtenerKpisFinancieros.serviciosMasSolicitados[*].servicioId").entityList(String.class).doesNotContain(services.get(0).toString());
    }

    @Test
    void flujoCajaEqualsObtenerFlujoCajaAndCobranzaPorMetodoIncludesAllEnums() {
        String start = "2045-03-01T00:00:00-04:00";
        String end = "2045-03-10T23:59:59-04:00";

        createMovement("INGRESO", "COBRO_CUOTA", "EFECTIVO", 150.00, "2045-03-02T10:00:00-04:00");
        createMovement("INGRESO", "COBRO_CUOTA", "QR_PAGOFACIL", 250.00, "2045-03-02T15:00:00-04:00");
        createMovement("INGRESO", "COBRO_CUOTA", "TRANSFERENCIA", 100.00, "2045-03-05T09:00:00-04:00");
        createMovement("EGRESO", "COMPRA_INSUMOS", "EFECTIVO", 80.00, "2045-03-05T11:00:00-04:00");

        var existingFlujo = graphQl.document("""
                query($start: DateTime!, $end: DateTime!) {
                  obtenerFlujoCaja(fechaInicio: $start, fechaFin: $end) {
                    totalIngresosBs
                    totalEgresosBs
                    saldoNetoBs
                  }
                }
                """).variable("start", start).variable("end", end).execute().path("obtenerFlujoCaja");

        var response = graphQl.document(FULL_QUERY)
                .variable("start", start)
                .variable("end", end)
                .execute();

        // Verify equality with existing flujo de caja
        assertThat(response.path("obtenerKpisFinancieros.flujoCaja.totalIngresosBs").entity(BigDecimal.class).get())
                .isEqualByComparingTo(existingFlujo.path("obtenerFlujoCaja.totalIngresosBs").entity(BigDecimal.class).get());
        assertThat(response.path("obtenerKpisFinancieros.flujoCaja.totalEgresosBs").entity(BigDecimal.class).get())
                .isEqualByComparingTo(existingFlujo.path("obtenerFlujoCaja.totalEgresosBs").entity(BigDecimal.class).get());
        assertThat(response.path("obtenerKpisFinancieros.flujoCaja.saldoNetoBs").entity(BigDecimal.class).get())
                .isEqualByComparingTo(existingFlujo.path("obtenerFlujoCaja.saldoNetoBs").entity(BigDecimal.class).get());

        // Verify daily breakdown in flujo
        response.path("obtenerKpisFinancieros.flujoCaja.seriePorDia[0].fecha").entity(String.class).isEqualTo("2045-03-02");
        response.path("obtenerKpisFinancieros.flujoCaja.seriePorDia[0].ingresosBs").entity(Double.class).isEqualTo(400.00);
        response.path("obtenerKpisFinancieros.flujoCaja.seriePorDia[0].egresosBs").entity(Double.class).isEqualTo(0.00);
        response.path("obtenerKpisFinancieros.flujoCaja.seriePorDia[0].saldoBs").entity(Double.class).isEqualTo(400.00);

        response.path("obtenerKpisFinancieros.flujoCaja.seriePorDia[1].fecha").entity(String.class).isEqualTo("2045-03-05");
        response.path("obtenerKpisFinancieros.flujoCaja.seriePorDia[1].ingresosBs").entity(Double.class).isEqualTo(100.00);
        response.path("obtenerKpisFinancieros.flujoCaja.seriePorDia[1].egresosBs").entity(Double.class).isEqualTo(80.00);
        response.path("obtenerKpisFinancieros.flujoCaja.seriePorDia[1].saldoBs").entity(Double.class).isEqualTo(20.00);

        // Cobranza por método: 3 elements in natural enum order
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[0].metodoPago").entity(String.class).isEqualTo("EFECTIVO");
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[0].cantidad").entity(Integer.class).isEqualTo(1);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[0].montoBs").entity(Double.class).isEqualTo(150.00);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[0].porcentajeMonto").entity(Float.class).isEqualTo(30.0f);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[0].montoNominal").entity(Boolean.class).isEqualTo(false);

        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[1].metodoPago").entity(String.class).isEqualTo("QR_PAGOFACIL");
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[1].cantidad").entity(Integer.class).isEqualTo(1);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[1].montoBs").entity(Double.class).isEqualTo(250.00);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[1].porcentajeMonto").entity(Float.class).isEqualTo(50.0f);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[1].montoNominal").entity(Boolean.class).isEqualTo(true);

        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[2].metodoPago").entity(String.class).isEqualTo("TRANSFERENCIA");
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[2].cantidad").entity(Integer.class).isEqualTo(1);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[2].montoBs").entity(Double.class).isEqualTo(100.00);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[2].porcentajeMonto").entity(Float.class).isEqualTo(20.0f);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[2].montoNominal").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void emptyPeriodReturnsZerosAndEmptyLists() {
        String start = "2038-01-01T00:00:00-04:00";
        String end = "2038-01-05T23:59:59-04:00";

        var response = graphQl.document(FULL_QUERY)
                .variable("start", start)
                .variable("end", end)
                .execute();

        response.path("obtenerKpisFinancieros.ingresos.totalBs").entity(Double.class).isEqualTo(0.00);
        response.path("obtenerKpisFinancieros.ingresos.cantidadMovimientos").entity(Integer.class).isEqualTo(0);
        response.path("obtenerKpisFinancieros.ingresos.montoNominalQrBs").entity(Double.class).isEqualTo(0.00);
        response.path("obtenerKpisFinancieros.ingresos.seriePorDia").entityList(Object.class).hasSize(0);

        response.path("obtenerKpisFinancieros.morosidad.cuotasExigibles").entity(Integer.class).isEqualTo(0);
        response.path("obtenerKpisFinancieros.morosidad.cuotasVencidas").entity(Integer.class).isEqualTo(0);
        response.path("obtenerKpisFinancieros.morosidad.porcentaje").entity(Float.class).isEqualTo(0.0f);
        response.path("obtenerKpisFinancieros.morosidad.montoVencidoBs").entity(Double.class).isEqualTo(0.00);
        response.path("obtenerKpisFinancieros.morosidad.detalle").entityList(Object.class).hasSize(0);

        response.path("obtenerKpisFinancieros.serviciosMasSolicitados").entityList(Object.class).hasSize(0);

        response.path("obtenerKpisFinancieros.flujoCaja.totalIngresosBs").entity(Double.class).isEqualTo(0.00);
        response.path("obtenerKpisFinancieros.flujoCaja.totalEgresosBs").entity(Double.class).isEqualTo(0.00);
        response.path("obtenerKpisFinancieros.flujoCaja.saldoNetoBs").entity(Double.class).isEqualTo(0.00);
        response.path("obtenerKpisFinancieros.flujoCaja.seriePorDia").entityList(Object.class).hasSize(0);

        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[0].metodoPago").entity(String.class).isEqualTo("EFECTIVO");
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[0].cantidad").entity(Integer.class).isEqualTo(0);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[0].montoBs").entity(Double.class).isEqualTo(0.00);
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[0].porcentajeMonto").entity(Float.class).isEqualTo(0.0f);

        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[1].metodoPago").entity(String.class).isEqualTo("QR_PAGOFACIL");
        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[1].montoNominal").entity(Boolean.class).isEqualTo(true);

        response.path("obtenerKpisFinancieros.cobranzaPorMetodo[2].metodoPago").entity(String.class).isEqualTo("TRANSFERENCIA");
    }

    @Test
    void seriesGroupsByLaPazDayAndBoundsAreInclusive() {
        createMovement("INGRESO", "COBRO_CUOTA", "EFECTIVO", 10.00, "2047-05-01T00:00:00-04:00"); // exactly fechaInicio
        createMovement("INGRESO", "COBRO_CUOTA", "EFECTIVO", 20.00, "2047-05-03T02:00:00Z");      // 2047-05-02 22:00 in La Paz
        createMovement("INGRESO", "COBRO_CUOTA", "EFECTIVO", 30.00, "2047-05-10T23:59:59-04:00"); // exactly fechaFin
        graphQl.document(FULL_QUERY)
                .variable("start", "2047-05-01T00:00:00-04:00").variable("end", "2047-05-10T23:59:59-04:00").execute()
                .path("obtenerKpisFinancieros.ingresos.cantidadMovimientos").entity(Integer.class).isEqualTo(3)
                .path("obtenerKpisFinancieros.ingresos.seriePorDia[*].fecha").entityList(String.class)
                .containsExactly("2047-05-01", "2047-05-02", "2047-05-10")
                .path("obtenerKpisFinancieros.ingresos.seriePorDia[1].montoBs").entity(Double.class).isEqualTo(20.00);
    }

    @Test
    void periodOfExactly366DaysIsAcceptedAndOneSecondMoreIsRejected() {
        graphQl.document(FULL_QUERY).variable("start", "2046-01-01T00:00:00Z")
                .variable("end", "2047-01-02T00:00:00Z").execute()
                .path("obtenerKpisFinancieros.ingresos.cantidadMovimientos").entity(Integer.class).isEqualTo(0);
        graphQl.document(FULL_QUERY).variable("start", "2046-01-01T00:00:00Z")
                .variable("end", "2047-01-02T00:00:01Z").execute()
                .errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.get(0).getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
                });
    }

    @Test
    void invalidPeriodReturnsBadRequest() {
        // Start after end
        graphQl.document(FULL_QUERY)
                .variable("start", "2026-05-10T00:00:00Z")
                .variable("end", "2026-05-01T00:00:00Z")
                .execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify();

        // Longer than 366 days (e.g. 370 days)
        graphQl.document(FULL_QUERY)
                .variable("start", "2026-01-01T00:00:00Z")
                .variable("end", "2027-01-06T00:00:00Z")
                .execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify();
    }

    @Test
    @WithMockUser(roles = "CAJERO")
    void cashierCannotAccessFinancialKpis() {
        graphQl.document(FULL_QUERY)
                .variable("start", "2026-01-01T00:00:00Z")
                .variable("end", "2026-01-10T00:00:00Z")
                .execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
    }

    @Test
    @WithMockUser(roles = "RECEPCIONISTA")
    void receptionistCannotAccessFinancialKpis() {
        graphQl.document(FULL_QUERY)
                .variable("start", "2026-01-01T00:00:00Z")
                .variable("end", "2026-01-10T00:00:00Z")
                .execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
    }

    @Test
    @WithMockUser(roles = "ODONTOLOGO")
    void dentistCannotAccessFinancialKpis() {
        graphQl.document(FULL_QUERY)
                .variable("start", "2026-01-01T00:00:00Z")
                .variable("end", "2026-01-10T00:00:00Z")
                .execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
    }

    @Test
    void queryIsReadOnlyAndWritesNothing() {
        String start = "2026-01-01T00:00:00Z";
        String end = "2026-01-31T23:59:59Z";

        String cashflowQuery = """
                query($start: DateTime!, $end: DateTime!) {
                  obtenerFlujoCaja(fechaInicio: $start, fechaFin: $end) {
                    totalIngresosBs
                    totalEgresosBs
                    saldoNetoBs
                    movimientos { id }
                  }
                }
                """;
        var cashflowBefore = graphQl.document(cashflowQuery)
                .variable("start", start).variable("end", end)
                .execute().path("obtenerFlujoCaja").entity(Object.class).get();

        UUID patient = createPatient("ReadOnlyTest");
        String quotaId = graphQl.document("""
                mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) {
                  cuotas { id }
                } }
                """).variable("input", Map.of("pacienteId", patient.toString(), "items", List.of(
                        Map.of("servicioId", "70000000-0000-0000-0000-000000000001", "cantidad", 1))))
                .execute().path("crearOrdenCobroManual.cuotas[0].id").entity(String.class).get();

        String rec1 = graphQl.document("""
                mutation($input: CobrarEfectivoInput!) { cobrarCuotaEnEfectivo(input: $input) { numeroRecibo } }
                """).variable("input", Map.of("cuotaId", quotaId, "usuarioCajeroId", CASHIER_ID.toString(),
                        "ciNitCliente", "0", "razonSocialCliente", "ReadOnlyTest"))
                .execute().path("cobrarCuotaEnEfectivo.numeroRecibo").entity(String.class).get();

        // Execute financial KPIs multiple times; each call must succeed so the invariance below is meaningful
        for (int i = 0; i < 2; i++) {
            graphQl.document(FULL_QUERY).variable("start", start).variable("end", end).execute()
                    .path("obtenerKpisFinancieros.cobranzaPorMetodo").entityList(Object.class).hasSize(3);
        }

        UUID patient2 = createPatient("ReadOnlyTest2");
        String quotaId2 = graphQl.document("""
                mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) {
                  cuotas { id }
                } }
                """).variable("input", Map.of("pacienteId", patient2.toString(), "items", List.of(
                        Map.of("servicioId", "70000000-0000-0000-0000-000000000001", "cantidad", 1))))
                .execute().path("crearOrdenCobroManual.cuotas[0].id").entity(String.class).get();

        String rec2 = graphQl.document("""
                mutation($input: CobrarEfectivoInput!) { cobrarCuotaEnEfectivo(input: $input) { numeroRecibo } }
                """).variable("input", Map.of("cuotaId", quotaId2, "usuarioCajeroId", CASHIER_ID.toString(),
                        "ciNitCliente", "0", "razonSocialCliente", "ReadOnlyTest2"))
                .execute().path("cobrarCuotaEnEfectivo.numeroRecibo").entity(String.class).get();

        long n1 = Long.parseLong(rec1.substring(rec1.lastIndexOf('-') + 1));
        long n2 = Long.parseLong(rec2.substring(rec2.lastIndexOf('-') + 1));
        assertThat(n2).isEqualTo(n1 + 1);

        var cashflowAfter = graphQl.document(cashflowQuery)
                .variable("start", start).variable("end", end)
                .execute().path("obtenerFlujoCaja").entity(Object.class).get();
        assertThat(cashflowAfter).isEqualTo(cashflowBefore);
    }
}
