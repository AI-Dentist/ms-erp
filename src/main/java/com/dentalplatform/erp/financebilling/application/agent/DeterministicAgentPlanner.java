package com.dentalplatform.erp.financebilling.application.agent;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rule-based stand-in for the language model, used ONLY by the development fallback. It maps a few instruction
 * patterns to the very same {@link ErpMcpToolsService} beans the model would call, so roles, validation, transactions
 * and audit are identical. It cannot reason: anything it does not recognize gets a help message, never an invented
 * answer. Write intents need explicit {@code clave=valor} data (see {@link #AYUDA}).
 */
@Component
public class DeterministicAgentPlanner {
    static final String AYUDA = "No entendí la instrucción. En modo de respaldo puedo: «resumen financiero» o "
            + "«monitorea mi negocio»; «cuotas vencidas»; «flujo de caja»; «reporte de ingresos» (por categoría, método "
            + "de pago o día), «reporte de órdenes por estado», «reporte de precios de servicios»; «enviar recordatorios "
            + "de cobranza»; «crear servicio codigo=XYZ nombre=\"Limpieza\" precio=150»; «registrar egreso monto=80 "
            + "categoria=INSUMOS descripcion=\"Guantes\"»; «anular orden <uuid> motivo=...»; «anular descuento <uuid>»; "
            + "«reprogramar <uuid> cuotas=3 primera=2026-12-01 intervalo=30»; "
            + "«Notificar cobro pendiente por WhatsApp, Telegram y Gmail» (demostración con datos sintéticos), "
            + "o notificación multicanal con pacienteNombre=…, telefono=…, montoBs=…, concepto=…, fechaVencimiento=… y canales=TODOS.";
    private static final Pattern UUID_PATTERN =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern WRITE_VERB = Pattern.compile(
            "\\b(crea|crear|nuevo|nueva|agrega|agregar|registra|registrar|anota|anotar|anula|anular|cancela|cancelar|elimina|eliminar|reprograma|reprogramar|quita|quitar)\\b");
    private static final String VALUE_END = "(?=\\s+(?:codigo|nombre|precio|monto|categoria|descripcion|metodo|motivo|cuotas|primera|intervalo|descuento)\\s*[:=]|[;]|$)";
    private static final DecimalFormat BS = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));

    private final ErpMcpToolsService tools;

    public DeterministicAgentPlanner(ErpMcpToolsService tools) { this.tools = tools; }

    /** Whether the instruction asks for a CRUD write; autonomous monitoring refuses these. */
    public static boolean pideEscritura(String instruction) {
        String text = plain(instruction);
        return WRITE_VERB.matcher(text).find() && (text.contains("servicio") || text.contains("egreso")
                || text.contains("gasto") || text.contains("orden") || text.contains("descuento")
                || text.contains("plan") || text.contains("cuota"));
    }

    public String planificar(String instruction) {
        String text = plain(instruction);
        if (isMultichannelInstruction(instruction)) { return notificacionMulticanal(instruction); }
        boolean write = WRITE_VERB.matcher(text).find();
        // Named argument values are data, not instructions (e.g. nombre="No debería").
        Matcher firstArgument = Pattern.compile("\\b(?:codigo|nombre|precio|monto|categoria|descripcion|metodo|motivo|cuotas|primera|intervalo|descuento)\\s*[:=]").matcher(text);
        String command = firstArgument.find() ? text.substring(0, firstArgument.start()) : text;
        // The fallback does not resolve compound/negated commands: refuse rather than risk an unintended write.
        if (write && Pattern.compile("\\b(no|nunca|jamas|ni|evita|evitar|not|never)\\b|don't").matcher(command).find()) {
            return "No ejecuté ninguna escritura: la instrucción contiene una negación. Indique una acción afirmativa y explícita si desea modificar datos.";
        }
        if (write && text.contains("servicio")) { return crearServicio(instruction); }
        if (write && (text.contains("egreso") || text.contains("gasto"))) { return registrarEgreso(instruction); }
        if (write && text.contains("descuento")) { return reprogramar(instruction, true); }
        if (write && text.contains("reprograma")) { return reprogramar(instruction, false); }
        if (write && text.contains("orden")) { return anularOrden(instruction); }
        if (text.contains("recordatorio") || text.contains("cobranza") || text.contains("whatsapp")) { return cobranza(instruction); }
        if (text.contains("vencid") || text.contains("moros") || text.contains("mora ") || text.endsWith("mora")
                || text.contains("deudor")) { return vencidas(); }
        if (text.contains("reporte") || text.contains("informe") || text.contains("grafico") || text.contains("excel")
                || text.contains("pdf") || text.contains("ingresos")) { return reporte(instruction); }
        if (text.contains("flujo") || text.contains("caja") || text.contains("egresos")) { return flujo(); }
        if (text.contains("resumen") || text.contains("monitorea") || text.contains("estado") || text.contains("finanzas")
                || text.contains("situacion") || text.contains("balance") || text.contains("negocio")) { return resumen(); }
        return AYUDA;
    }

    // ---- reads ----------------------------------------------------------------------------------------------------

    public String resumen() {
        return resumen(tools.obtenerResumenFinancieroTool(null, null));
    }

    public String resumen(ErpMcpToolsService.ResumenFinancieroTool r) {
        BigDecimal income = r.porMetodoPago().stream().map(ErpMcpToolsService.FlujoMetodoTool::ingresosBs).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal expense = r.porMetodoPago().stream().map(ErpMcpToolsService.FlujoMetodoTool::egresosBs).reduce(BigDecimal.ZERO, BigDecimal::add);
        return "Resumen financiero del " + r.desde() + " al " + r.hasta() + ": ingresos " + bs(income) + ", egresos "
                + bs(expense) + ", saldo neto " + bs(income.subtract(expense)) + ". Órdenes de cobro pendientes: "
                + r.ordenesPendientes() + ", parciales: " + r.ordenesParciales() + ". Deuda pendiente total: "
                + bs(r.saldoPendienteTotalBs()) + ".";
    }

    public String vencidas() {
        return vencidas(tools.listarPacientesConCuotasVencidasTool(null));
    }

    public String vencidas(ErpMcpToolsService.CuotasVencidasTool r) {
        if (r.totalCuotasVencidas() == 0) { return "No hay cuotas vencidas impagas."; }
        StringBuilder text = new StringBuilder("Hay ").append(r.totalCuotasVencidas()).append(" cuota(s) vencida(s) por ")
                .append(bs(r.montoTotalVencidoBs())).append(":");
        r.cuotas().stream().limit(10).forEach(c -> text.append("\n- ").append(c.paciente()).append(": cuota ")
                .append(c.numeroCuota()).append(" de ").append(bs(c.montoBs())).append(", ").append(c.diasAtraso())
                .append(" día(s) de atraso (orden ").append(c.ordenCobroId()).append(")"));
        if (r.cuotas().size() > 10 || r.truncado()) { text.append("\n… y más; use un reporte para el detalle completo."); }
        return text.toString();
    }

    private String flujo() {
        var r = tools.obtenerFlujoCajaTool(null, null);
        return "Flujo de caja del " + r.desde() + " al " + r.hasta() + ": ingresos " + bs(r.totalIngresosBs())
                + ", egresos " + bs(r.totalEgresosBs()) + ", saldo neto " + bs(r.saldoNetoBs()) + " en "
                + r.movimientos() + " movimiento(s).";
    }

    private String reporte(String instruction) {
        var r = tools.generarReporteDinamicoSqlTool(instruction);
        return "Reporte generado (" + r.totalFilas() + " fila(s), gráfico sugerido " + r.tipoGraficoSugerido()
                + "). Columnas: " + String.join(", ", r.columnas()) + ". Descargas: Excel " + r.urlDescargaExcel()
                + " y PDF " + r.urlDescargaPdf() + ".";
    }

    private String cobranza(String instruction) {
        Matcher ids = UUID_PATTERN.matcher(instruction);
        List<String> list = ids.results().map(java.util.regex.MatchResult::group).toList();
        var r = tools.dispararAlertaCobranzaN8nTool(list.isEmpty() ? null : list);
        if (!r.notificacionEnviada()) { return "No se envió ningún recordatorio: " + r.motivo() + "."; }
        return "Recordatorios de cobranza enviados por n8n: " + r.enviados() + " (omitidos por duplicado en 24 h: "
                + r.omitidosPorDuplicado() + ", fallidos: " + r.fallidos() + ")"
                + (r.motivo() == null ? "." : ". " + r.motivo() + ".");
    }

    private static boolean isMultichannelInstruction(String instruction) {
        var argument = Pattern.compile("(?i)\\b(pacienteNombre|telefono|email|telegramChatId|montoBs|concepto|fechaVencimiento|canales)\\s*[:=]").matcher(instruction);
        boolean hasData = argument.find();
        String head = plain(hasData ? instruction.substring(0, argument.start()) : instruction);
        // Existing CRUD commands and their quoted data retain their previous dispatch and validation.
        if (WRITE_VERB.matcher(head).find()) { return false; }
        boolean notify = Pattern.compile("\\b(notific(?:ar|a|ame)|avis(?:ar|a|ame))\\b").matcher(head).find();
        boolean sendMultichannel = head.contains("multicanal") && Pattern.compile("\\b(envi(?:ar|a)|mand(?:ar|a))\\b").matcher(head).find();
        return (notify || sendMultichannel) && (hasData || head.contains("multicanal") || head.contains("whatsapp")
                || head.contains("telegram") || head.contains("gmail"));
    }

    private String notificacionMulticanal(String instruction) {
        // Separate grammar: adding notification keys must not change existing CRUD parsing/defaults.
        var pattern = Pattern.compile("(?i)\\b(pacienteNombre|telefono|email|telegramChatId|montoBs|concepto|fechaVencimiento|canales)\\s*[:=]\\s*(?:\"([^\"]*)\"|'([^']*)'|(\\S+))");
        var matcher = pattern.matcher(instruction);
        java.util.Map<String,String> values = new java.util.HashMap<>();
        int first = instruction.length();
        while (matcher.find()) {
            first = Math.min(first, matcher.start());
            String value = matcher.group(2) != null ? matcher.group(2) : matcher.group(3) != null ? matcher.group(3) : matcher.group(4);
            if (values.put(matcher.group(1).toLowerCase(Locale.ROOT), value) != null) {
                throw new BusinessRuleException("Un parámetro multicanal está repetido");
            }
        }
        if (Pattern.compile("\\b(no|nunca|jamas|ni|evita|evitar|not|never)\\b").matcher(plain(instruction.substring(0, first))).find()) {
            return "No ejecuté ninguna notificación: la instrucción contiene una negación.";
        }
        boolean demo = values.isEmpty();
        if (demo && Pattern.compile("(?i)\\b(pacienteNombre|telefono|email|telegramChatId|montoBs|concepto|fechaVencimiento|canales)\\s*[:=]").matcher(instruction).find()) {
            throw new BusinessRuleException("Un parámetro multicanal tiene un valor no válido");
        }
        BigDecimal amount;
        try { amount = demo ? new BigDecimal("350.00") : new BigDecimal(values.getOrDefault("montobs", "")); }
        catch (NumberFormatException e) { throw new BusinessRuleException("Indique montoBs numérico para notificar"); }
        var result = tools.dispararNotificacionMulticanal(demo ? "Juan Pérez" : values.get("pacientenombre"),
                demo ? "+59170012345" : values.get("telefono"), values.get("email"), values.get("telegramchatid"), amount,
                demo ? "Cuota 2/3 - Tratamiento de Ortodoncia" : values.get("concepto"),
                demo ? "2026-11-05" : values.get("fechavencimiento"), values.get("canales"));
        String channels = result.resultadosPorCanal().stream().map(row -> row.canal() + "=" + row.estado())
                .collect(java.util.stream.Collectors.joining(", "));
        return "Notificación multicanal" + (demo ? " de demostración (datos sintéticos)" : "") + ": "
                + result.modoEjecucion() + ". " + channels
                + ("SIMULADO_SIN_N8N".equals(result.modoEjecucion()) ? ". Simulación: entrega real no confirmada." : "");
    }

    // ---- writes (explicit data required) ------------------------------------------------------------------------

    private String crearServicio(String instruction) {
        String code = value(instruction, "codigo", "[A-Za-z0-9._-]+");
        String name = value(instruction, "nombre", null);
        BigDecimal price = number(value(instruction, "precio(?:\\s*bs)?", "\\d+(?:[.,]\\d{1,2})?"));
        if (code == null || name == null || price == null) {
            throw new BusinessRuleException("Para crear un servicio indique codigo=…, nombre=… y precio=… (en Bs)");
        }
        var s = tools.crearServicioOdontologicoTool(code, name, price, value(instruction, "descripcion", null));
        return "Servicio creado: " + s.codigo() + " — " + s.nombre() + " a " + bs(s.precioBs()) + " (id " + s.id() + ").";
    }

    private String registrarEgreso(String instruction) {
        BigDecimal amount = number(value(instruction, "monto", "\\d+(?:[.,]\\d{1,2})?"));
        String category = value(instruction, "categoria", "[A-Za-z0-9._ -]+?");
        String description = value(instruction, "descripcion", null);
        if (amount == null || category == null || description == null) {
            throw new BusinessRuleException("Para registrar un egreso indique monto=…, categoria=… y descripcion=…");
        }
        var e = tools.registrarEgresoCajaTool(amount, category, description, value(instruction, "metodo", "[A-Za-z_]+"));
        return "Egreso registrado: " + bs(e.montoBs()) + " en " + e.categoria() + " por " + e.metodoPago()
                + " (id " + e.movimientoId() + ").";
    }

    private String anularOrden(String instruction) {
        String id = uuid(instruction);
        String reason = value(instruction, "motivo", null);
        if (id == null || reason == null) {
            throw new BusinessRuleException("Para anular una orden indique su UUID y motivo=…");
        }
        var o = tools.anularOrdenCobroPendienteTool(id, reason);
        return "Orden de cobro " + o.ordenCobroId() + " anulada (estado " + o.estado() + ").";
    }

    private String reprogramar(String instruction, boolean annulDiscount) {
        String id = uuid(instruction);
        if (id == null) { throw new BusinessRuleException("Indique el UUID de la orden de cobro"); }
        BigDecimal discount = annulDiscount ? BigDecimal.ZERO : number(value(instruction, "descuento", "\\d+(?:[.,]\\d{1,2})?"));
        String count = value(instruction, "cuotas", "\\d+");
        String interval = value(instruction, "intervalo", "\\d+");
        String first = value(instruction, "primera(?:\\s+cuota)?", "\\d{4}-\\d{2}-\\d{2}");
        if (discount == null && count == null && interval == null && first == null) {
            throw new BusinessRuleException("Indique qué reprogramar: cuotas=…, primera=AAAA-MM-DD, intervalo=… o descuento=…");
        }
        var p = tools.reprogramarPlanPagosOAnularDescuentoTool(id, discount, count == null ? null : Integer.valueOf(count),
                first, interval == null ? null : Integer.valueOf(interval));
        return "Plan de la orden " + p.ordenCobroId() + " actualizado: " + p.cuotas() + " cuota(s), descuento "
                + bs(p.descuentoBs()) + ", total " + bs(p.totalBs()) + ".";
    }

    // ---- parsing helpers ------------------------------------------------------------------------------------------

    private static String uuid(String text) {
        Matcher m = UUID_PATTERN.matcher(text);
        return m.find() ? m.group() : null;
    }

    /** {@code key=value}, {@code key: "quoted value"}; null pattern = free text up to the next key. */
    private static String value(String text, String key, String shape) {
        Pattern quoted = Pattern.compile("(?i)\\b" + key + "\\s*[:=]\\s*[\"“']([^\"”']+)[\"”']");
        Matcher q = quoted.matcher(text);
        if (q.find()) { return q.group(1).strip(); }
        String body = shape == null ? "(.+?)" : "(" + shape + ")";
        Matcher m = Pattern.compile("(?i)\\b" + key + "\\s*[:=]\\s*" + body + VALUE_END).matcher(text);
        if (m.find()) { return m.group(1).strip(); }
        // Explicit malformed values must not be interpreted as omitted optional values/defaults.
        if (Pattern.compile("(?i)\\b" + key + "\\s*[:=]").matcher(text).find()) {
            throw new BusinessRuleException("Un parámetro explícito tiene un valor no válido");
        }
        return null;
    }

    private static BigDecimal number(String value) {
        return value == null ? null : new BigDecimal(value.replace(',', '.'));
    }

    private static String bs(BigDecimal amount) { return "Bs " + BS.format(amount); }

    private static String plain(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
