package com.dentalplatform.erp.financebilling.agent;

import com.dentalplatform.erp.financebilling.application.command.ConfigurarPlanPagosCommand;
import com.dentalplatform.erp.financebilling.application.usecase.ConfigurarPlanPagosUseCase;
import com.dentalplatform.erp.financebilling.domain.model.ModalidadPago;
import com.dentalplatform.erp.financebilling.domain.model.OrdenCobro;
import com.dentalplatform.erp.financebilling.domain.model.OrdenCobroItem;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.shared.domain.vo.Money;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.dentalplatform.erp.financebilling.agent.AgentSupport.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Profile {@code dev} without an AI key: the deterministic planner drives the very same tools. Each scenario asserts
 * the GraphQL answer AND the persisted effects (rows, audit, history) or their absence.
 */
@ActiveProfiles("dev")
@WithMockUser(username = "admin", roles = "ADMIN")
class AgentFallbackIntegrationTest extends IntegrationTestBase {
    static final UUID PATIENT = UUID.fromString("60000000-0000-0000-0000-000000000001");
    static final UUID SERVICE = UUID.fromString("70000000-0000-0000-0000-000000000001");
    static final UUID ADMIN_ID = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired OrdenCobroRepository orders;
    @Autowired ConfigurarPlanPagosUseCase configure;
    @Autowired PlatformTransactionManager manager;
    @Autowired WebApplicationContext web;
    MockMvc mvc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) { AgentSupport.reportingProperties(registry); }

    @BeforeEach
    void setUp() {
        AgentSupport.prepareReportingRole(jdbc);
        mvc = MockMvcBuilders.webAppContextSetup(web).apply(springSecurity()).build();
    }

    private OrdenCobro order() {
        return new TransactionTemplate(manager).execute(s -> orders.guardar(OrdenCobro.crear(PATIENT, null, null,
                List.of(OrdenCobroItem.crear(SERVICE, null, 1, Money.of("997.01"))), null, OffsetDateTime.now())));
    }

    private static void as(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a",
                AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private org.springframework.graphql.test.tester.GraphQlTester.Response run(String instruction) {
        return graphQl.document(EXECUTE).variable("i", instruction).execute();
    }

    private String field(String instruction, String path) {
        return run(instruction).path("ejecutarAgenteErp." + path).entity(String.class).get();
    }

    private Map<String, Object> interaction(String id) {
        return jdbc.queryForMap("SELECT modo, bloqueada, usuario_id, solicitante, tools::text AS tools, accion_crud, n8n_enviada, reporte_id "
                + "FROM agente_interacciones WHERE id = ?", UUID.fromString(id));
    }

    @Test
    void createsAServiceThroughTheUseCaseWithAuditAndAHistoryRow() {
        String code = "AGT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var response = run("Crea un servicio codigo=" + code + " nombre=\"Limpieza profunda\" precio=150.50");
        response.path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class).matches(t -> t.startsWith(LABEL) && t.contains(code))
                .path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).containsExactly("crearServicioOdontologicoTool")
                .path("ejecutarAgenteErp.notificacionN8nEnviada").entity(Boolean.class).isEqualTo(false)
                .path("ejecutarAgenteErp.reporteGenerado").valueIsNull();
        String id = response.path("ejecutarAgenteErp.interaccionId").entity(String.class).get();
        UUID serviceId = jdbc.queryForObject("SELECT id FROM servicios_odontologicos WHERE codigo = ?", UUID.class, code);
        response.path("ejecutarAgenteErp.accionCrudEjecutada").entity(String.class)
                .isEqualTo("crearServicioOdontologicoTool:" + serviceId);
        assertThat(jdbc.queryForMap("SELECT nombre, precio_base_bs FROM servicios_odontologicos WHERE id = ?", serviceId))
                .containsEntry("nombre", "Limpieza profunda").containsEntry("precio_base_bs", new BigDecimal("150.50"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'SERVICIO_ODONTOLOGICO_CREADO' AND entidad_id = ? AND usuario_id = ?",
                Integer.class, serviceId, ADMIN_ID)).isEqualTo(1);
        assertThat(interaction(id)).containsEntry("modo", "RESPALDO").containsEntry("bloqueada", false)
                .containsEntry("usuario_id", ADMIN_ID).containsEntry("accion_crud", "crearServicioOdontologicoTool:" + serviceId);
        assertThat(interaction(id).get("tools").toString()).isEqualTo("[\"crearServicioOdontologicoTool\"]");
    }

    @Test
    void incompleteCrudDataExecutesNothingAndSaysSo() {
        int services = count(jdbc, "servicios_odontologicos"), audits = count(jdbc, "audit_logs");
        var response = run("Crea un servicio codigo=INCOMPLETO");
        response.path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class)
                .matches(t -> t.startsWith(LABEL) && t.contains("No se pudo completar") && t.contains("nombre"))
                .path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).hasSize(0)
                .path("ejecutarAgenteErp.accionCrudEjecutada").valueIsNull();
        assertThat(count(jdbc, "servicios_odontologicos")).isEqualTo(services);
        assertThat(count(jdbc, "audit_logs")).isEqualTo(audits);
    }

    @Test
    void registersACashExpenseAsTheCallingCashier() {
        String category = "INSUMOS-" + UUID.randomUUID().toString().substring(0, 6);
        var response = run("Registrar egreso monto=80.25 categoria=" + category + " descripcion=\"Guantes de nitrilo\"");
        response.path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).containsExactly("registrarEgresoCajaTool");
        String crud = response.path("ejecutarAgenteErp.accionCrudEjecutada").entity(String.class).get();
        UUID movement = UUID.fromString(crud.substring(crud.indexOf(':') + 1));
        assertThat(jdbc.queryForMap("SELECT tipo_movimiento, categoria, metodo_pago, monto_bs, usuario_cajero_id, descripcion FROM movimientos_caja WHERE id = ?", movement))
                .containsEntry("tipo_movimiento", "EGRESO").containsEntry("categoria", category)
                .containsEntry("metodo_pago", "EFECTIVO").containsEntry("monto_bs", new BigDecimal("80.25"))
                .containsEntry("usuario_cajero_id", ADMIN_ID).containsEntry("descripcion", "Guantes de nitrilo");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'EGRESO_CAJA_REGISTRADO' AND entidad_id = ?",
                Integer.class, movement)).isEqualTo(1);
    }

    @Test
    void annulsAPendingOrderLogicallyWithItsReason() {
        var order = order();
        var response = run("Anular orden " + order.getId() + " motivo=\"Error de registro\"");
        response.path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).containsExactly("anularOrdenCobroPendienteTool")
                .path("ejecutarAgenteErp.accionCrudEjecutada").entity(String.class)
                .isEqualTo("anularOrdenCobroPendienteTool:" + order.getId());
        assertThat(jdbc.queryForMap("SELECT estado, saldo_pendiente_bs FROM ordenes_cobro WHERE id = ?", order.getId()))
                .containsEntry("estado", "ANULADO").containsEntry("saldo_pendiente_bs", new BigDecimal("0.00"));
        assertThat(jdbc.queryForObject("SELECT detalle_json ->> 'motivo' FROM audit_logs WHERE accion = 'ORDEN_COBRO_ANULADA' AND entidad_id = ?",
                String.class, order.getId())).isEqualTo("Error de registro");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ordenes_cobro WHERE id = ?", Integer.class, order.getId())).isEqualTo(1);
    }

    @Test
    void cancelsADiscountAndReschedulesAPendingPlan() {
        var order = order();
        configure.ejecutar(new ConfigurarPlanPagosCommand(order.getId(), ModalidadPago.CREDITO, new BigDecimal("10.00"), 3,
                LocalDate.now().plusDays(10), 30));
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'PLAN_PAGOS_CONFIGURADO' AND entidad_id = ?", Integer.class, order.getId());

        run("Anular descuento " + order.getId()).path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class)
                .containsExactly("reprogramarPlanPagosOAnularDescuentoTool");
        assertThat(jdbc.queryForObject("SELECT descuento_bs FROM ordenes_cobro WHERE id = ?", BigDecimal.class, order.getId()))
                .isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuotas_pago WHERE orden_cobro_id = ?", Integer.class, order.getId())).isEqualTo(3);

        run("Reprogramar " + order.getId() + " cuotas=2 intervalo=15");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuotas_pago WHERE orden_cobro_id = ?", Integer.class, order.getId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'PLAN_PAGOS_CONFIGURADO' AND entidad_id = ?",
                Integer.class, order.getId())).isEqualTo(audits + 2);
    }

    @Test
    void readOnlyIntentsRunMonitoringToolsWithoutAnyWrite() {
        int services = count(jdbc, "servicios_odontologicos"), movements = count(jdbc, "movimientos_caja"),
                orders = count(jdbc, "ordenes_cobro");
        for (String[] intent : new String[][]{{"Dame el resumen financiero", "obtenerResumenFinancieroTool", "Resumen financiero"},
                {"¿Qué cuotas vencidas hay?", "listarPacientesConCuotasVencidasTool", "cuota"},
                {"Muéstrame el flujo de caja", "obtenerFlujoCajaTool", "Flujo de caja"}}) {
            run(intent[0]).path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).containsExactly(intent[1])
                    .path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class)
                    .matches(t -> t.startsWith(LABEL) && t.contains(intent[2]))
                    .path("ejecutarAgenteErp.accionCrudEjecutada").valueIsNull();
        }
        assertThat(count(jdbc, "servicios_odontologicos")).isEqualTo(services);
        assertThat(count(jdbc, "movimientos_caja")).isEqualTo(movements);
        assertThat(count(jdbc, "ordenes_cobro")).isEqualTo(orders);
    }

    @Test
    void unknownInstructionGetsAHelpMessageNotAnInventedAnswer() {
        run("cuéntame un chiste").path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class)
                .matches(t -> t.startsWith(LABEL) && t.contains("No entendí la instrucción"))
                .path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).hasSize(0);
    }

    @Test
    void reportExposesCanonicalFieldsAlongsideLegacyFields() {
        var response = graphQl.document("""
                mutation { ejecutarAgenteErp(instruccion: "Genera un reporte de precios de servicios") {
                  reporteGenerado { titulo sqlGenerado consultaSqlEjecutada filas columnas filasJson resumenAnalitico tipoGraficoSugerido }
                } }
                """).execute();
        response.path("ejecutarAgenteErp.reporteGenerado.titulo").entity(String.class).isEqualTo("Genera un reporte de precios de servicios");
        String sql = response.path("ejecutarAgenteErp.reporteGenerado.sqlGenerado").entity(String.class).get();
        response.path("ejecutarAgenteErp.reporteGenerado.consultaSqlEjecutada").entity(String.class).isEqualTo(sql)
                .path("ejecutarAgenteErp.reporteGenerado.tipoGraficoSugerido").entity(String.class).matches(s -> List.of("BARRAS", "LINEAS", "TORTA").contains(s))
                .path("ejecutarAgenteErp.reporteGenerado.resumenAnalitico").entity(String.class).matches(s -> s.contains("registro") && s.contains("code"));
        assertThat(response.path("ejecutarAgenteErp.reporteGenerado.filas").entityList(List.class).get()).isNotEmpty();
        assertThat(JSON.readTree(response.path("ejecutarAgenteErp.reporteGenerado.filasJson").entity(String.class).get()).size()).isPositive();
    }

    @Test
    void reportRunsTheGuardedTemplateAndBothDownloadsAreRealFiles() throws Exception {
        var clinic = graphQl.document("{ obtenerConfiguracionEmpresa { nombreComercial nit } }").execute()
                .path("obtenerConfiguracionEmpresa").entity(java.util.Map.class).get();
        String clinicName = clinic.get("nombreComercial").toString();
        String clinicNit = clinic.get("nit").toString();
        var response = run("Genera un reporte de precios de servicios");
        response.path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).containsExactly("generarReporteDinamicoSqlTool")
                .path("ejecutarAgenteErp.reporteGenerado.columnas").entityList(String.class).containsExactly("code", "price_bs")
                .path("ejecutarAgenteErp.reporteGenerado.consultaSqlEjecutada").entity(String.class)
                .isEqualTo("SELECT code, price_bs FROM report_services WHERE active = true ORDER BY code ASC LIMIT 100")
                .path("ejecutarAgenteErp.reporteGenerado.tipoGraficoSugerido").entity(String.class).matches(t -> List.of("BARRAS", "TABLA").contains(t));
        String id = response.path("ejecutarAgenteErp.reporteGenerado.reporteId").entity(String.class).get();
        String interactionId = response.path("ejecutarAgenteErp.interaccionId").entity(String.class).get();
        response.path("ejecutarAgenteErp.reporteGenerado.urlDescargaExcel").entity(String.class).isEqualTo("/api/v1/reports/export/" + id + "/xlsx")
                .path("ejecutarAgenteErp.reporteGenerado.urlDescargaPdf").entity(String.class).isEqualTo("/api/v1/reports/export/" + id + "/pdf");
        String rows = response.path("ejecutarAgenteErp.reporteGenerado.filasJson").entity(String.class).get();
        assertThat(JSON.readTree(rows).size()).isPositive();
        assertThat(interaction(interactionId).get("reporte_id")).isEqualTo(UUID.fromString(id));

        byte[] xlsx = mvc.perform(get("/api/v1/reports/export/" + id + "/xlsx")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andReturn().getResponse().getContentAsByteArray();
        try (var book = new XSSFWorkbook(new ByteArrayInputStream(xlsx))) {
            var sheet = book.getSheetAt(0);
            assertThat(sheet.getRow(0).getCell(0).getStringCellValue()).isEqualTo(clinicName);
            assertThat(sheet.getRow(8).getCell(0).getStringCellValue()).startsWith("Consulta: Genera un reporte de precios");
            assertThat(sheet.getRow(10).getCell(1).getStringCellValue()).isEqualTo("price_bs");
            var amount = sheet.getRow(11).getCell(1);
            assertThat(amount.getCellType()).isEqualTo(org.apache.poi.ss.usermodel.CellType.NUMERIC);
            assertThat(amount.getCellStyle().getDataFormatString()).isEqualTo("\"Bs \"#,##0.00");
        }
        byte[] pdf = mvc.perform(get("/api/v1/reports/export/" + id + "/pdf")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf")).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 4)).isEqualTo("%PDF");
        try (var reader = new org.openpdf.text.pdf.PdfReader(pdf)) {
            assertThat(new org.openpdf.text.pdf.parser.PdfTextExtractor(reader).getTextFromPage(1))
                    .contains(clinicName, "NIT: " + clinicNit, "Bs ");
        }
    }

    @Test
    void reportOfAnUnsupportedTopicSaysSoWithoutRunningAnything() {
        run("Genera un reporte de odontólogos favoritos").path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class)
                .matches(t -> t.contains("No se pudo completar") && t.contains("respaldo determinístico"))
                .path("ejecutarAgenteErp.reporteGenerado").valueIsNull();
    }

    @Test
    void cashierCannotCreateServicesThroughTheAgentAndNothingChanges() {
        as("agent-cajero", "CAJERO");
        String code = "DENY-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        int services = count(jdbc, "servicios_odontologicos");
        run("Crea un servicio codigo=" + code + " nombre=\"No debería\" precio=5").errors()
                .expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        assertThat(count(jdbc, "servicios_odontologicos")).isEqualTo(services);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos WHERE codigo = ?", Integer.class, code)).isZero();
        Map<String, Object> row = jdbc.queryForMap("SELECT respuesta, tools::text AS tools, accion_crud FROM agente_interacciones WHERE solicitante = 'agent-cajero'");
        assertThat(row.get("respuesta").toString()).contains("no autorizada");
        assertThat(row.get("tools").toString()).isEqualTo("[]");
        assertThat(row.get("accion_crud")).isNull();
    }

    @Test
    void receptionistCannotRunReportsThroughTheAgent() {
        as("agent-recepcion", "RECEPCIONISTA");
        int history = count(jdbc, "agente_interacciones");
        run("Genera un reporte de ingresos").errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        assertThat(count(jdbc, "agente_interacciones")).isEqualTo(history + 1);
        assertThat(jdbc.queryForObject("SELECT reporte_id FROM agente_interacciones WHERE solicitante = 'agent-recepcion'", UUID.class)).isNull();
    }

    @Test
    void historyShowsEverythingToAdminAndOnlyOwnRowsToOtherRoles() {
        String tag = UUID.randomUUID().toString().substring(0, 6);
        as("hist-cajero-" + tag, "CAJERO");
        run("Dame el resumen financiero");
        as("hist-recep-" + tag, "RECEPCIONISTA");
        run("Muéstrame el flujo de caja");
        var recepHistory = graphQl.document(HISTORY).variable("l", 100).execute()
                .path("listarHistorialAgente[*].instruccionOriginal").entityList(String.class).get();
        assertThat(recepHistory).containsExactly("Muéstrame el flujo de caja");

        as("hist-cajero-" + tag, "CAJERO");
        var cashierHistory = graphQl.document(HISTORY).variable("l", 100).execute()
                .path("listarHistorialAgente[*].instruccionOriginal").entityList(String.class).get();
        assertThat(cashierHistory).containsExactly("Dame el resumen financiero");

        as("hist-admin-" + tag, "ADMIN");
        var adminHistory = graphQl.document(HISTORY).variable("l", 100).execute()
                .path("listarHistorialAgente[*].instruccionOriginal").entityList(String.class).get();
        assertThat(adminHistory).contains("Dame el resumen financiero", "Muéstrame el flujo de caja");
        var limited = graphQl.document(HISTORY).variable("l", 1).execute()
                .path("listarHistorialAgente").entityList(Object.class).get();
        assertThat(limited).hasSize(1);
    }
    @Test
    void negatedWriteDoesNotCreateAService() {
        String code = "NEG-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        int services = count(jdbc, "servicios_odontologicos"), audits = count(jdbc, "audit_logs");
        run("No crear servicio codigo=" + code + " nombre=Prueba precio=15")
                .path("ejecutarAgenteErp.accionCrudEjecutada").valueIsNull()
                .path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).hasSize(0);
        assertThat(count(jdbc, "servicios_odontologicos")).isEqualTo(services);
        assertThat(count(jdbc, "audit_logs")).isEqualTo(audits);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos WHERE codigo = ?", Integer.class, code)).isZero();
    }

    @Test
    void explicitDescriptionIsPreserved() {
        String code = "DESC-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        var result = run("Crear servicio codigo=" + code + " nombre=Prueba precio=15 descripcion=Conservadora");
        result.path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).containsExactly("crearServicioOdontologicoTool");
        UUID id = jdbc.queryForObject("SELECT id FROM servicios_odontologicos WHERE codigo = ?", UUID.class, code);
        assertThat(jdbc.queryForObject("SELECT descripcion FROM servicios_odontologicos WHERE id = ?", String.class, id))
                .isEqualTo("Conservadora");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'SERVICIO_ODONTOLOGICO_CREADO' AND entidad_id = ?",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void invalidExplicitIntervalDoesNotBecomeADefault() {
        var order = order();
        String detail = "query($id: UUID!) { obtenerOrdenCobroDetalle(id: $id) { id estado montoTotalBs saldoPendienteBs cuotas { id numeroCuota montoCuotaBs fechaVencimiento estado } } }";
        var before = graphQl.document(detail).variable("id", order.getId()).execute()
                .path("obtenerOrdenCobroDetalle").entity(Map.class).get();
        int audits = count(jdbc, "audit_logs");
        run("Reprogramar " + order.getId() + " cuotas=2 intervalo=-1")
                .path("ejecutarAgenteErp.accionCrudEjecutada").valueIsNull()
                .path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).hasSize(0)
                .path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class).matches(t -> t.contains("No se pudo completar"));
        assertThat(graphQl.document(detail).variable("id", order.getId()).execute()
                .path("obtenerOrdenCobroDetalle").entity(Map.class).get()).isEqualTo(before);
        assertThat(count(jdbc, "audit_logs")).isEqualTo(audits);
    }

    @Test
    void explicitReportFilterIsAppliedOrTheRequestIsRejected() {
        var response = run("Genera un reporte de ingresos por metodo de pago solo QR_PAGOFACIL");
        var report = response.path("ejecutarAgenteErp.reporteGenerado").entity(Map.class).get();
        if (report == null) {
            response.path("ejecutarAgenteErp.accionCrudEjecutada").valueIsNull()
                    .path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).hasSize(0);
        } else {
            assertThat(report.get("consultaSqlEjecutada").toString()).contains("WHERE payment_method = 'QR_PAGOFACIL'");
            var rows = JSON.readTree(report.get("filasJson").toString());
            for (var row : rows) { assertThat(row.get(0).asString()).isEqualTo("QR_PAGOFACIL"); }
        }
    }

}
