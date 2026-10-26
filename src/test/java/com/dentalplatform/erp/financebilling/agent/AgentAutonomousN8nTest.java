package com.dentalplatform.erp.financebilling.agent;

import com.dentalplatform.erp.financebilling.application.command.ConfigurarPlanPagosCommand;
import com.dentalplatform.erp.financebilling.application.port.CobranzaRecordatorioPort;
import com.dentalplatform.erp.financebilling.application.usecase.ConfigurarPlanPagosUseCase;
import com.dentalplatform.erp.financebilling.domain.model.ModalidadPago;
import com.dentalplatform.erp.financebilling.domain.model.OrdenCobro;
import com.dentalplatform.erp.financebilling.domain.model.OrdenCobroItem;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.shared.domain.vo.Money;
import com.dentalplatform.erp.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.dentalplatform.erp.financebilling.agent.AgentSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/** Autonomous "Monitorea mi negocio" with n8n switched ON (a real local HTTP server plays the webhook). */
@ActiveProfiles("dev")
@WithMockUser(username = "admin", roles = "ADMIN")
class AgentAutonomousN8nTest extends IntegrationTestBase {
    static final UUID PATIENT = UUID.fromString("60000000-0000-0000-0000-000000000001");
    static final UUID SERVICE = UUID.fromString("70000000-0000-0000-0000-000000000001");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<JsonNode> received = new CopyOnWriteArrayList<>();
    static final AtomicInteger status = new AtomicInteger(200);
    static final HttpServer server = startServer();

    @Autowired OrdenCobroRepository orders;
    @Autowired ConfigurarPlanPagosUseCase configure;
    @Autowired PlatformTransactionManager manager;
    @Autowired CobranzaRecordatorioPort ledger;
    UUID quota;

    static HttpServer startServer() {
        try {
            var http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.createContext("/n8n", exchange -> {
                received.add(JSON.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
                exchange.sendResponseHeaders(status.get(), -1);
                exchange.close();
            });
            http.start();
            return http;
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("erp.n8n.enabled", () -> "true");
        registry.add("erp.n8n.webhook-url", () -> "http://127.0.0.1:" + server.getAddress().getPort() + "/n8n");
        AgentSupport.reportingProperties(registry);
    }

    @AfterAll
    static void stop() { server.stop(0); }

    @BeforeEach
    void overdueInstallment() {
        status.set(200);
        var order = new TransactionTemplate(manager).execute(s -> orders.guardar(OrdenCobro.crear(PATIENT, null, null,
                List.of(OrdenCobroItem.crear(SERVICE, null, 1, Money.of("250.00"))), null, OffsetDateTime.now())));
        // Year 1750 sorts before any other test's overdue installment, so it is inside the 20-reminder cap.
        configure.ejecutar(new ConfigurarPlanPagosCommand(order.getId(), ModalidadPago.CONTADO, BigDecimal.ZERO, 1,
                LocalDate.of(1750, 1, 1), 1));
        quota = jdbc.queryForObject("SELECT id FROM cuotas_pago WHERE orden_cobro_id = ?", UUID.class, order.getId());
        received.clear();
    }

    private static void as(String username, String role) {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(username, "n/a",
                AuthorityUtils.createAuthorityList("ROLE_" + role)));
    }

    private org.springframework.graphql.test.tester.GraphQlTester.Response autonomous(String instruction) {
        return graphQl.document(EXECUTE).variable("i", instruction).variable("a", true).execute();
    }

    private List<JsonNode> mine() {
        return received.stream().filter(n -> quota.toString().equals(n.path("cuotaId").asString())).toList();
    }

    private int crudAudits() {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion IN ('SERVICIO_ODONTOLOGICO_CREADO', "
                + "'EGRESO_CAJA_REGISTRADO', 'ORDEN_COBRO_ANULADA', 'PLAN_PAGOS_CONFIGURADO')", Integer.class);
    }

    private int ledgerRows() {
        return jdbc.queryForObject("SELECT count(*) FROM cobranza_recordatorios WHERE cuota_id = ?", Integer.class, quota);
    }

    @Test
    void autonomousMonitoringSummarizesRemindsOverdueInstallmentsThroughN8nAndNeverWrites() {
        int services = count(jdbc, "servicios_odontologicos"), movements = count(jdbc, "movimientos_caja");
        var response = autonomous("Monitorea mi negocio");
        response.path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class).containsExactly(
                        "obtenerResumenFinancieroTool", "listarPacientesConCuotasVencidasTool", "dispararAlertaCobranzaN8nTool")
                .path("ejecutarAgenteErp.notificacionN8nEnviada").entity(Boolean.class).isEqualTo(true)
                .path("ejecutarAgenteErp.accionCrudEjecutada").valueIsNull()
                .path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class)
                .matches(t -> t.startsWith(LABEL) && t.contains("Resumen financiero") && t.contains("Recordatorios de cobranza por n8n"));
        String id = response.path("ejecutarAgenteErp.interaccionId").entity(String.class).get();

        assertThat(mine()).hasSize(1);
        JsonNode payload = mine().getFirst();
        assertThat(payload.path("event").asString()).isEqualTo("cobranza.recordatorio");
        assertThat(payload.path("montoBs").decimalValue()).isEqualByComparingTo("250.00");
        assertThat(payload.path("vencimiento").asString()).isEqualTo("1750-01-01");
        assertThat(payload.path("numeroCuota").asInt()).isEqualTo(1);
        assertThat(payload.path("telefonoWhatsapp").asString()).isNotBlank();
        assertThat(payload.path("nombrePaciente").asString()).isNotBlank();
        assertThat(ledgerRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'COBRANZA_RECORDATORIO_ENVIADO' AND entidad_id = ?",
                Integer.class, quota)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT n8n_enviada FROM agente_interacciones WHERE id = ?", Boolean.class, UUID.fromString(id))).isTrue();
        assertThat(count(jdbc, "servicios_odontologicos")).isEqualTo(services);
        assertThat(count(jdbc, "movimientos_caja")).isEqualTo(movements);

        // Same installment again inside 24h: monitoring still works, but no second reminder is sent or recorded.
        autonomous("Monitorea mi negocio").path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class)
                .contains("dispararAlertaCobranzaN8nTool");
        assertThat(mine()).hasSize(1);
        assertThat(ledgerRows()).isEqualTo(1);
    }

    @Test
    void autonomousModeRefusesCrudEvenWhenTheInstructionAsksForIt() {
        int services = count(jdbc, "servicios_odontologicos"), movements = count(jdbc, "movimientos_caja"),
                orders = count(jdbc, "ordenes_cobro"), crudAudits = crudAudits();
        String code = "AUTO-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        autonomous("Monitorea y además crea un servicio codigo=" + code + " nombre=\"Hack\" precio=1")
                .path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class)
                .matches(t -> t.contains("El modo autónomo solo monitorea") && t.contains("Resumen financiero"))
                .path("ejecutarAgenteErp.toolsMcpInvocadas").entityList(String.class)
                .matches(l -> !l.contains("crearServicioOdontologicoTool") && !l.contains("registrarEgresoCajaTool")
                        && !l.contains("anularOrdenCobroPendienteTool") && !l.contains("reprogramarPlanPagosOAnularDescuentoTool"))
                .path("ejecutarAgenteErp.accionCrudEjecutada").valueIsNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM servicios_odontologicos WHERE codigo = ?", Integer.class, code)).isZero();
        assertThat(count(jdbc, "servicios_odontologicos")).isEqualTo(services);
        assertThat(count(jdbc, "movimientos_caja")).isEqualTo(movements);
        assertThat(count(jdbc, "ordenes_cobro")).isEqualTo(orders);
        assertThat(crudAudits()).isEqualTo(crudAudits);
    }

    @Test
    void cashierMonitoringSendsRemindersButReceptionistIsDeniedBeforeAnyDataIsReadOrSent() {
        as("n8n-recep", "RECEPCIONISTA");
        int history = count(jdbc, "agente_interacciones");
        autonomous("Monitorea mi negocio").errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        assertThat(received).isEmpty();
        assertThat(ledgerRows()).isZero();
        assertThat(count(jdbc, "agente_interacciones")).isEqualTo(history + 1);

        as("n8n-cajero", "CAJERO");
        autonomous("Monitorea mi negocio").path("ejecutarAgenteErp.notificacionN8nEnviada").entity(Boolean.class).isEqualTo(true);
        assertThat(mine()).hasSize(1);
    }

    @Test
    void failedDeliveryReleasesTheClaimSoTheReminderCanBeRetriedAndNeverPretendsDelivery() {
        status.set(500);
        var failed = graphQl.document(EXECUTE).variable("i", "Enviar recordatorio de cobranza " + quota).execute();
        failed.path("ejecutarAgenteErp.notificacionN8nEnviada").entity(Boolean.class).isEqualTo(false)
                .path("ejecutarAgenteErp.respuestaNaturalAgente").entity(String.class)
                .matches(t -> t.contains("No se envió ningún recordatorio") && t.contains("n8n no aceptó"));
        assertThat(mine()).hasSize(1);
        assertThat(ledgerRows()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'COBRANZA_RECORDATORIO_ENVIADO' AND entidad_id = ?",
                Integer.class, quota)).isZero();

        status.set(200);
        graphQl.document(EXECUTE).variable("i", "Enviar recordatorio de cobranza " + quota).execute()
                .path("ejecutarAgenteErp.notificacionN8nEnviada").entity(Boolean.class).isEqualTo(true);
        assertThat(mine()).hasSize(2);
        assertThat(ledgerRows()).isEqualTo(1);
    }

    @Test
    void reminderLedgerIsAtomicAndReleasable() {
        OffsetDateTime now = OffsetDateTime.now();
        assertThat(ledger.reservar(quota, null, "ledger-test", now, Duration.ofHours(24))).isTrue();
        assertThat(ledger.reservar(quota, null, "ledger-test", now.plusMinutes(5), Duration.ofHours(24))).isFalse();
        assertThat(ledger.reservar(quota, null, "ledger-test", now.plusHours(25), Duration.ofHours(24))).isTrue();
        assertThat(ledgerRows()).isEqualTo(2);
        ledger.liberar(quota, now.plusHours(25));
        assertThat(ledgerRows()).isEqualTo(1);
    }
    @Test
    void successfulReminderDoesNotDisableUnpaidPlanRescheduling() {
        graphQl.document(EXECUTE).variable("i", "Enviar recordatorio de cobranza " + quota).execute()
                .path("ejecutarAgenteErp.notificacionN8nEnviada").entity(Boolean.class).isEqualTo(true);
        UUID orderId = jdbc.queryForObject("SELECT orden_cobro_id FROM cuotas_pago WHERE id = ?", UUID.class, quota);
        graphQl.document("mutation($input: ConfigurarPlanPagosInput!) { configurarPlanDePagos(input: $input) { id cuotas { id } } }")
                .variable("input", java.util.Map.of("ordenCobroId", orderId.toString(), "modalidadPago", "CREDITO",
                        "descuentoBs", "0.00", "cantidadCuotas", 2, "fechaPrimeraCuota", LocalDate.now().plusDays(7).toString(),
                        "intervaloDias", 7)).execute()
                .path("configurarPlanDePagos.cuotas").entityList(Object.class).hasSize(2);
        assertThat(ledgerRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cuotas_pago WHERE id = ?", Integer.class, quota)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = 'PLAN_PAGOS_CONFIGURADO' AND entidad_id = ?",
                Integer.class, orderId)).isEqualTo(2);
    }

}
