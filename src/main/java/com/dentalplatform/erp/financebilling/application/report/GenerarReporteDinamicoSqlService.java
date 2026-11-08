package com.dentalplatform.erp.financebilling.application.report;

import com.dentalplatform.erp.financebilling.application.agent.AgentProvider;
import com.dentalplatform.erp.financebilling.application.agent.DeterministicReportTemplates;
import com.dentalplatform.erp.financebilling.application.port.ReportQueryPort;
import com.dentalplatform.erp.financebilling.application.port.SqlGeneratorPort;
import com.dentalplatform.erp.financebilling.domain.report.ReportResult;
import com.dentalplatform.erp.financebilling.domain.report.SqlReadOnlyGuardrail;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The ONE Text-to-SQL path, internal engine of {@code generarReporteDinamicoSqlTool}. The SQL (written by Spring AI,
 * or by a fixed template in the deterministic fallback) must pass {@link SqlReadOnlyGuardrail} before it runs on the
 * dedicated read-only reporting datasource; the result is cached per owner under a fresh {@code reporteId}.
 * The generator is {@code @Lazy}: Spring AI resolves every ToolCallbackProvider while it builds the chat model, and the
 * tools depend on this service, so an eager generator (which needs the chat model) would close a bean cycle.
 */
@Service
public class GenerarReporteDinamicoSqlService {
    static final String EXPORT_BASE = "/api/v1/reports/export/";

    private final SqlGeneratorPort generator;
    private final ReportQueryPort query;
    private final ReportStore store;
    private final JsonMapper json;
    private final AgentProvider provider;
    private final Clock clock;
    private final SqlReadOnlyGuardrail guard = new SqlReadOnlyGuardrail();

    public GenerarReporteDinamicoSqlService(@Lazy SqlGeneratorPort generator, ReportQueryPort query, ReportStore store,
                                            JsonMapper json, AgentProvider provider, Clock clock) {
        this.generator = generator;
        this.query = query;
        this.store = store;
        this.json = json;
        this.provider = provider;
        this.clock = clock;
    }

    public record Generado(ReportResponse reporte, ReportResult datos) { }

    private static String buildTitulo(String solicitud) {
        String clean = solicitud.strip();
        return clean.length() <= 80 ? clean : clean.substring(0, 80) + "…";
    }

    private static String buildResumenAnalitico(ReportResult result, String titulo) {
        int rows = result.rows().size();
        int cols = result.columns().size();
        String cols_str = String.join(", ", result.columns());
        return String.format("Informe: %s. Se obtuvieron %d registro%s con %d columna%s (%s).",
                titulo, rows, rows == 1 ? "" : "s", cols, cols == 1 ? "" : "s", cols_str);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    public Generado ejecutar(String solicitud) {
        if (solicitud == null || solicitud.isBlank() || solicitud.length() > 500) {
            throw new BusinessRuleException("La pregunta debe tener entre 1 y 500 caracteres");
        }
        String generated = provider.modo() == AgentProvider.Modo.RESPALDO
                ? DeterministicReportTemplates.resolver(solicitud, LocalDate.now(clock))
                : generator.generate(solicitud);
        String sql = guard.validate(generated);
        ReportResult result = query.execute(sql);
        UUID id = UUID.randomUUID();
        String titulo = buildTitulo(solicitud);
        List<List<String>> filas = result.rows().stream()
                .map(row -> row.stream().map(v -> v == null ? "" : v.toString()).toList())
                .toList();
        String resumenAnalitico = buildResumenAnalitico(result, titulo);
        String chart = ReportChartAdvisor.sugerir(result);
        if ("TABLA".equals(chart)) { chart = "BARRAS"; }
        var response = new ReportResponse(id, solicitud, sql, result.columns(), json.writeValueAsString(result.rows()),
                chart, EXPORT_BASE + id + "/xlsx", EXPORT_BASE + id + "/pdf",
                titulo, sql, filas, resumenAnalitico);
        store.put(SecurityContextHolder.getContext().getAuthentication().getName(), response, result);
        return new Generado(response, result);
    }
}
