package com.dentalplatform.erp.financebilling.agent;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;

import java.util.UUID;

/** Shared fixtures of the agent tests: GraphQL documents and the restricted reporting login of the embedded cluster. */
final class AgentSupport {
    static final String EXECUTE = """
            mutation($i: String!, $a: Boolean = false) { ejecutarAgenteErp(instruccion: $i, modoAutonomoMonitoreo: $a) {
              interaccionId fechaEjecucion instruccionOriginal respuestaNaturalAgente toolsMcpInvocadas
              accionCrudEjecutada notificacionN8nEnviada
              reporteGenerado { reporteId consultaSqlEjecutada columnas filasJson tipoGraficoSugerido urlDescargaExcel urlDescargaPdf }
            } }
            """;
    static final String HISTORY = """
            query($l: Int) { listarHistorialAgente(limite: $l) { interaccionId instruccionOriginal respuestaNaturalAgente
              toolsMcpInvocadas accionCrudEjecutada notificacionN8nEnviada } }
            """;
    static final String LABEL = "[Respaldo determinístico — sin IA]";
    static final String ROLE = "agent_report_" + UUID.randomUUID().toString().replace("-", "");
    static final String PASSWORD = UUID.randomUUID().toString();

    private AgentSupport() { }

    /** Points the dedicated reporting datasource at a fresh SELECT-only login of the disposable embedded cluster. */
    static void reportingProperties(DynamicPropertyRegistry registry) {
        registry.add("erp.reports.db-url", () -> EmbeddedPostgresSupport.jdbcUrl("erp_it").replace("?user=postgres", ""));
        registry.add("erp.reports.db-user", () -> ROLE);
        registry.add("erp.reports.db-password", () -> PASSWORD);
    }

    static void prepareReportingRole(JdbcTemplate jdbc) {
        jdbc.execute("DO $$ BEGIN IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = '" + ROLE + "') THEN "
                + "CREATE ROLE " + ROLE + " LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOREPLICATION NOBYPASSRLS PASSWORD '" + PASSWORD + "'; END IF; END $$");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + ROLE);
        jdbc.execute("GRANT SELECT ON report_income, report_orders, report_services TO " + ROLE);
    }

    static int count(JdbcTemplate jdbc, String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }
}
