package com.dentalplatform.erp.financebilling.application.agent;

import com.dentalplatform.erp.financebilling.application.command.RegistrarEgresoCommand;
import com.dentalplatform.erp.financebilling.application.command.ReprogramarPlanPagosCommand;
import com.dentalplatform.erp.financebilling.application.port.MonitoreoNegocioPort.CuotaVencida;
import com.dentalplatform.erp.financebilling.application.report.GenerarReporteDinamicoSqlService;
import com.dentalplatform.erp.financebilling.application.usecase.AnularOrdenCobroUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.ConsultarFinanzasUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.DispararAlertaCobranzaUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.MonitoreoNegocioUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.RegistrarEgresoCajaUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.ReprogramarPlanPagosUseCase;
import com.dentalplatform.erp.financebilling.domain.model.MetodoPago;
import com.dentalplatform.erp.mastercatalog.application.command.CrearServicioOdontologicoCommand;
import com.dentalplatform.erp.mastercatalog.application.usecase.CrearServicioOdontologicoUseCase;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The ERP tools, exposed both to the agent (Spring AI tool calling) and to the MCP server at {@code /mcp}.
 * <p>
 * Security model: a tool never receives an identity from its caller. It runs with the {@code SecurityContext} of the
 * request thread, and each method carries the SAME role expression as the equivalent GraphQL operation (the use cases
 * add their own checks, audit and transactions). Writes go only through use cases: this class holds no repository
 * and no SQL. Results are compact, PII-minimal records (patients are shown as "Nombre I.", never CI/NIT or phone).
 */
@Service
public class ErpMcpToolsService {
    private static final String ADMIN = "hasRole('ADMIN')";
    private static final String CASH = "hasAnyRole('ADMIN','CAJERO')";
    private static final String READ_OR_PLAN = "hasAnyRole('ADMIN','CAJERO','RECEPCIONISTA')";
    static final int MAX_OVERDUE_ROWS = 50;
    static final int SAMPLE_ROWS = 10;

    private final MonitoreoNegocioUseCase monitoring;
    private final ConsultarFinanzasUseCase finances;
    private final CrearServicioOdontologicoUseCase createService;
    private final RegistrarEgresoCajaUseCase expense;
    private final ReprogramarPlanPagosUseCase reschedule;
    private final AnularOrdenCobroUseCase annul;
    private final GenerarReporteDinamicoSqlService reports;
    private final DispararAlertaCobranzaUseCase collections;
    private final AgentActorResolver actor;
    private final AgentToolTrace trace;
    private final Clock clock;

    public ErpMcpToolsService(MonitoreoNegocioUseCase monitoring, ConsultarFinanzasUseCase finances,
                              CrearServicioOdontologicoUseCase createService, RegistrarEgresoCajaUseCase expense,
                              ReprogramarPlanPagosUseCase reschedule, AnularOrdenCobroUseCase annul,
                              GenerarReporteDinamicoSqlService reports, DispararAlertaCobranzaUseCase collections,
                              AgentActorResolver actor, AgentToolTrace trace, Clock clock) {
        this.monitoring = monitoring;
        this.finances = finances;
        this.createService = createService;
        this.expense = expense;
        this.reschedule = reschedule;
        this.annul = annul;
        this.reports = reports;
        this.collections = collections;
        this.actor = actor;
        this.trace = trace;
        this.clock = clock;
    }

    // ---- Monitoring and reads -------------------------------------------------------------------------------

    @Tool(description = "Resumen financiero: ingresos, egresos y saldo neto por método de pago en un rango de fechas "
            + "(por defecto el mes en curso), más órdenes de cobro pendientes/parciales y deuda pendiente total en Bs. "
            + "Solo lectura. Fechas en formato AAAA-MM-DD.")
    @ToolCategory(ToolCategory.MONITOREO)
    @PreAuthorize(CASH)
    public ResumenFinancieroTool obtenerResumenFinancieroTool(
            @ToolParam(required = false, description = "Fecha inicial AAAA-MM-DD (por defecto el día 1 del mes en curso)") String desde,
            @ToolParam(required = false, description = "Fecha final AAAA-MM-DD, inclusive (por defecto hoy)") String hasta) {
        var range = range(desde, hasta);
        var summary = monitoring.resumenFinanciero(range[0], range[1]);
        var result = new ResumenFinancieroTool(range[0].toLocalDate(), range[1].toLocalDate(),
                summary.porMetodoPago().stream().map(m -> new FlujoMetodoTool(m.metodoPago().name(), m.ingresosBs(),
                        m.egresosBs(), m.saldoNetoBs())).toList(),
                summary.ordenesPendientes(), summary.ordenesParciales(), summary.saldoPendienteTotalBs());
        trace.executed("obtenerResumenFinancieroTool");
        return result;
    }

    @Tool(description = "Lista las cuotas vencidas e impagas (morosidad) con paciente abreviado, monto en Bs y días de "
            + "atraso, ordenadas de la más antigua a la más reciente. Máximo 50 filas. Solo lectura, sin CI ni teléfono.")
    @ToolCategory(ToolCategory.MONITOREO)
    @PreAuthorize(CASH)
    public CuotasVencidasTool listarPacientesConCuotasVencidasTool(
            @ToolParam(required = false, description = "Fecha de corte AAAA-MM-DD (por defecto hoy)") String fechaCorte) {
        LocalDate cutoff = fechaCorte == null || fechaCorte.isBlank() ? LocalDate.now(clock) : date(fechaCorte);
        List<CuotaVencida> overdue = monitoring.cuotasVencidas(cutoff);
        BigDecimal total = overdue.stream().map(CuotaVencida::montoBs).reduce(BigDecimal.ZERO, BigDecimal::add);
        var rows = overdue.stream().limit(MAX_OVERDUE_ROWS).map(c -> new CuotaVencidaTool(c.pacienteId(),
                abbreviate(c.nombreCompleto()), c.ordenId(), c.cuotaId(), c.numeroCuota(), c.montoBs(),
                c.vencimiento(), c.diasAtraso())).toList();
        trace.executed("listarPacientesConCuotasVencidasTool");
        return new CuotasVencidasTool(overdue.size(), total.setScale(2), rows.size() < overdue.size(), rows);
    }

    @Tool(description = "Flujo de caja de un rango de fechas (por defecto el mes en curso): total de ingresos, egresos, "
            + "saldo neto en Bs y cantidad de movimientos. Solo lectura. Fechas en formato AAAA-MM-DD.")
    @ToolCategory(ToolCategory.MONITOREO)
    @PreAuthorize(READ_OR_PLAN)
    public FlujoCajaTool obtenerFlujoCajaTool(
            @ToolParam(required = false, description = "Fecha inicial AAAA-MM-DD (por defecto el día 1 del mes en curso)") String desde,
            @ToolParam(required = false, description = "Fecha final AAAA-MM-DD, inclusive (por defecto hoy)") String hasta) {
        var range = range(desde, hasta);
        var flow = finances.obtenerFlujoCaja(range[0], range[1]);
        trace.executed("obtenerFlujoCajaTool");
        return new FlujoCajaTool(range[0].toLocalDate(), range[1].toLocalDate(), flow.totalIngresosBs(),
                flow.totalEgresosBs(), flow.saldoNetoBs(), flow.movimientos().size());
    }

    // ---- CRUD, always through use cases (validation + @Transactional + audit_logs) ---------------------------

    @Tool(description = "Crea un servicio odontológico en el catálogo (arancel en Bs). Solo ADMIN. Úsela únicamente "
            + "cuando el usuario lo pida explícitamente e indique código, nombre y precio.")
    @ToolCategory(ToolCategory.ACCION_CRUD)
    @PreAuthorize(ADMIN)
    public ServicioCreadoTool crearServicioOdontologicoTool(
            @ToolParam(description = "Código único del servicio, p. ej. LIM-PROF") String codigo,
            @ToolParam(description = "Nombre del servicio") String nombre,
            @ToolParam(description = "Precio base en Bolivianos (Bs), p. ej. 150.00") BigDecimal precioBs,
            @ToolParam(required = false, description = "Descripción opcional") String descripcion) {
        var service = createService.ejecutar(new CrearServicioOdontologicoCommand(null, codigo, nombre, descripcion, precioBs));
        trace.executed("crearServicioOdontologicoTool");
        trace.crud("crearServicioOdontologicoTool", service.getId());
        return new ServicioCreadoTool(service.getId(), service.getCodigo(), service.getNombre(),
                service.getPrecioBase().amount());
    }

    @Tool(description = "Registra un egreso de caja (gasto) en Bs a nombre del usuario que ejecuta la acción. ADMIN o "
            + "CAJERO. Úsela únicamente cuando el usuario lo pida explícitamente e indique monto, categoría y descripción.")
    @ToolCategory(ToolCategory.ACCION_CRUD)
    @PreAuthorize(CASH)
    public EgresoRegistradoTool registrarEgresoCajaTool(
            @ToolParam(description = "Monto del egreso en Bs, mayor a cero") BigDecimal montoBs,
            @ToolParam(description = "Categoría del gasto, p. ej. INSUMOS") String categoria,
            @ToolParam(description = "Descripción del gasto") String descripcion,
            @ToolParam(required = false, description = "EFECTIVO (por defecto), QR_PAGOFACIL o TRANSFERENCIA") String metodoPago) {
        UUID cashier = actor.usuarioId().orElseThrow(() ->
                new BusinessRuleException("No se pudo identificar su usuario cajero para registrar el egreso"));
        MetodoPago method;
        try { method = metodoPago == null || metodoPago.isBlank() ? MetodoPago.EFECTIVO : MetodoPago.valueOf(metodoPago.strip().toUpperCase()); }
        catch (IllegalArgumentException e) { throw new BusinessRuleException("Método de pago no válido: " + metodoPago); }
        var movement = expense.ejecutar(new RegistrarEgresoCommand(cashier, categoria, method, montoBs, descripcion));
        trace.executed("registrarEgresoCajaTool");
        trace.crud("registrarEgresoCajaTool", movement.id());
        return new EgresoRegistradoTool(movement.id(), movement.categoria(), movement.metodoPago().name(),
                movement.monto().amount());
    }

    @Tool(description = "Reprograma el plan de pagos de una orden con cuotas pendientes (cantidad de cuotas, primera fecha, "
            + "intervalo en días) o anula/cambia su descuento (descuentoBs = 0 elimina el descuento). Solo parámetros "
            + "indicados cambian. ADMIN, CAJERO o RECEPCIONISTA. No altera cuotas ya pagadas.")
    @ToolCategory(ToolCategory.ACCION_CRUD)
    @PreAuthorize(READ_OR_PLAN)
    public PlanReprogramadoTool reprogramarPlanPagosOAnularDescuentoTool(
            @ToolParam(description = "UUID de la orden de cobro") String ordenCobroId,
            @ToolParam(required = false, description = "Nuevo descuento en Bs; 0 anula el descuento") BigDecimal descuentoBs,
            @ToolParam(required = false, description = "Nueva cantidad de cuotas (1 = contado)") Integer cantidadCuotas,
            @ToolParam(required = false, description = "Fecha de la primera cuota AAAA-MM-DD") String fechaPrimeraCuota,
            @ToolParam(required = false, description = "Días entre cuotas") Integer intervaloDias) {
        var order = reschedule.ejecutar(new ReprogramarPlanPagosCommand(uuid(ordenCobroId, "ordenCobroId"), null,
                descuentoBs, cantidadCuotas, fechaPrimeraCuota == null || fechaPrimeraCuota.isBlank() ? null : date(fechaPrimeraCuota),
                intervaloDias));
        trace.executed("reprogramarPlanPagosOAnularDescuentoTool");
        trace.crud("reprogramarPlanPagosOAnularDescuentoTool", order.getId());
        return new PlanReprogramadoTool(order.getId(), order.getEstado().name(), order.getDescuento().amount(),
                order.getTotal().amount(), order.getCuotas().size());
    }

    @Tool(description = "Anula (baja lógica a ANULADO) una orden de cobro PENDIENTE sin pagos registrados, con motivo "
            + "obligatorio. ADMIN o CAJERO. No se puede deshacer.")
    @ToolCategory(ToolCategory.ACCION_CRUD)
    @PreAuthorize(CASH)
    public OrdenAnuladaTool anularOrdenCobroPendienteTool(
            @ToolParam(description = "UUID de la orden de cobro") String ordenCobroId,
            @ToolParam(description = "Motivo de la anulación") String motivo) {
        var order = annul.ejecutar(uuid(ordenCobroId, "ordenCobroId"), motivo);
        trace.executed("anularOrdenCobroPendienteTool");
        trace.crud("anularOrdenCobroPendienteTool", order.getId());
        return new OrdenAnuladaTool(order.getId(), order.getEstado().name());
    }

    // ---- Reports (read-only Text-to-SQL) ----------------------------------------------------------------------

    @Tool(description = "Genera un reporte a partir de una solicitud en lenguaje natural: traduce a un SELECT de "
            + "PostgreSQL sobre las vistas de reportes aprobadas (ingresos, órdenes por estado, precios de servicios), "
            + "lo valida como solo lectura y lo ejecuta. Devuelve columnas, una muestra de filas, el gráfico sugerido "
            + "(BARRAS, LINEAS, TORTA o TABLA) y los enlaces de descarga Excel/PDF. Solo ADMIN.")
    @ToolCategory(ToolCategory.REPORTE)
    @PreAuthorize(ADMIN)
    public ReporteTool generarReporteDinamicoSqlTool(
            @ToolParam(description = "Solicitud del reporte en lenguaje natural, hasta 500 caracteres") String solicitud) {
        var generated = reports.ejecutar(solicitud);
        var report = generated.reporte();
        trace.executed("generarReporteDinamicoSqlTool");
        trace.report(report.reporteId());
        return new ReporteTool(report.reporteId(), report.consultaSqlEjecutada(), report.columnas(),
                generated.datos().rows().size(), generated.datos().rows().stream().limit(SAMPLE_ROWS).toList(),
                report.tipoGraficoSugerido(), report.urlDescargaExcel(), report.urlDescargaPdf());
    }

    // ---- Collection automation (n8n) --------------------------------------------------------------------------

    @Tool(description = "Envía por n8n recordatorios de cobro por WhatsApp de cuotas vencidas (incluye los datos de la "
            + "cuota y el QR PagoFácil vigente si existe). Sin cuotaIds recuerda las más antiguas; máximo 20 por llamada y "
            + "nunca repite la misma cuota en 24 horas. Si n8n está deshabilitado no envía nada. ADMIN o CAJERO.")
    @ToolCategory(ToolCategory.COBRANZA)
    @PreAuthorize(CASH)
    public AlertaCobranzaTool dispararAlertaCobranzaN8nTool(
            @ToolParam(required = false, description = "UUIDs de las cuotas a recordar; vacío = cuotas vencidas más antiguas") List<String> cuotaIds) {
        List<UUID> ids = new ArrayList<>();
        if (cuotaIds != null) { cuotaIds.forEach(id -> ids.add(uuid(id, "cuotaIds"))); }
        var result = collections.ejecutar(ids);
        trace.executed("dispararAlertaCobranzaN8nTool");
        trace.n8nSent(result.notificacionEnviada());
        return new AlertaCobranzaTool(result.notificacionEnviada(), result.enviados(), result.omitidosPorDuplicado(),
                result.fallidos(), result.noElegibles(), result.motivo());
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    private OffsetDateTime[] range(String desde, String hasta) {
        LocalDate today = LocalDate.now(clock);
        LocalDate from = desde == null || desde.isBlank() ? today.withDayOfMonth(1) : date(desde);
        LocalDate to = hasta == null || hasta.isBlank() ? today : date(hasta);
        var zone = clock.getZone();
        return new OffsetDateTime[]{from.atStartOfDay(zone).toOffsetDateTime(),
                to.atTime(LocalTime.MAX).atZone(zone).toOffsetDateTime()};
    }

    private static LocalDate date(String value) {
        try { return LocalDate.parse(value.strip()); }
        catch (DateTimeParseException e) { throw new BusinessRuleException("Fecha no válida (use AAAA-MM-DD): " + value); }
    }

    private static UUID uuid(String value, String field) {
        try { return UUID.fromString(value.strip()); }
        catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessRuleException("El identificador " + field + " no es un UUID válido");
        }
    }

    /** "Juan Carlos Pérez Gómez" becomes "Juan G." — enough to recognise a person, no full identity. */
    static String abbreviate(String fullName) {
        if (fullName == null || fullName.isBlank()) { return "Paciente"; }
        String[] parts = fullName.strip().split("\\s+");
        return parts.length == 1 ? parts[0] : parts[0] + " " + parts[parts.length - 1].charAt(0) + ".";
    }

    // ---- tool results (compact, JSON-able) ----------------------------------------------------------------------

    public record FlujoMetodoTool(String metodoPago, BigDecimal ingresosBs, BigDecimal egresosBs, BigDecimal saldoNetoBs) { }
    public record ResumenFinancieroTool(LocalDate desde, LocalDate hasta, List<FlujoMetodoTool> porMetodoPago,
                                        long ordenesPendientes, long ordenesParciales, BigDecimal saldoPendienteTotalBs) { }
    public record CuotaVencidaTool(UUID pacienteId, String paciente, UUID ordenCobroId, UUID cuotaId, int numeroCuota,
                                   BigDecimal montoBs, LocalDate vencimiento, long diasAtraso) { }
    public record CuotasVencidasTool(int totalCuotasVencidas, BigDecimal montoTotalVencidoBs, boolean truncado,
                                     List<CuotaVencidaTool> cuotas) { }
    public record FlujoCajaTool(LocalDate desde, LocalDate hasta, BigDecimal totalIngresosBs, BigDecimal totalEgresosBs,
                                BigDecimal saldoNetoBs, int movimientos) { }
    public record ServicioCreadoTool(UUID id, String codigo, String nombre, BigDecimal precioBs) { }
    public record EgresoRegistradoTool(UUID movimientoId, String categoria, String metodoPago, BigDecimal montoBs) { }
    public record PlanReprogramadoTool(UUID ordenCobroId, String estado, BigDecimal descuentoBs, BigDecimal totalBs,
                                       int cuotas) { }
    public record OrdenAnuladaTool(UUID ordenCobroId, String estado) { }
    public record ReporteTool(UUID reporteId, String consultaSqlEjecutada, List<String> columnas, int totalFilas,
                              List<List<Object>> muestraFilas, String tipoGraficoSugerido, String urlDescargaExcel,
                              String urlDescargaPdf) { }
    public record AlertaCobranzaTool(boolean notificacionEnviada, int enviados, int omitidosPorDuplicado, int fallidos,
                                     int noElegibles, String motivo) { }
}
