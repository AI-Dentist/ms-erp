package com.dentalplatform.erp.financebilling.infrastructure.report;

import com.dentalplatform.erp.financebilling.application.port.ReportQueryPort;
import com.dentalplatform.erp.financebilling.domain.report.ReportResult;
import com.dentalplatform.erp.financebilling.domain.report.SqlReadOnlyGuardrail;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/** Dedicated lazy pool, intentionally NOT a DataSource bean (must not replace Boot's writable datasource). */
@Component
public class ReportingJdbcQueryAdapter implements ReportQueryPort, AutoCloseable {
    private final String url;
    private final String username;
    private final String password;
    private final String mainUsername;
    private HikariDataSource pool;
    private final SqlReadOnlyGuardrail guard = new SqlReadOnlyGuardrail();

    public ReportingJdbcQueryAdapter(@Value("${erp.reports.db-url:${spring.datasource.url}}") String url,
                                     @Value("${erp.reports.db-user:}") String username,
                                     @Value("${erp.reports.db-password:}") String password,
                                     @Value("${spring.datasource.username}") String mainUsername) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.mainUsername = mainUsername;
    }

    private synchronized HikariDataSource dataSource() {
        if (username.isBlank() || password.isBlank() || username.equalsIgnoreCase(mainUsername)
                || url.matches("(?is).*[?&](user|password)=.*")) {
            throw new BusinessRuleException("Reportes no disponibles: configure credenciales de solo lectura independientes");
        }
        if (pool == null) {
            var config = new HikariConfig();
            config.setJdbcUrl(url);
            config.setUsername(username);
            config.setPassword(password);
            config.setPoolName("erp-reporting");
            config.setMaximumPoolSize(2);
            config.setMinimumIdle(0);
            config.setConnectionTimeout(3000);
            config.setValidationTimeout(1000);
            config.setInitializationFailTimeout(-1);
            config.setReadOnly(true);
            config.addDataSourceProperty("connectTimeout", "3");
            config.addDataSourceProperty("socketTimeout", "5");
            pool = new HikariDataSource(config);
        }
        return pool;
    }

    @Override
    public ReportResult execute(String input) {
        String sql = guard.validate(input);
        try (Connection connection = dataSource().getConnection()) {
            connection.setReadOnly(true);
            connection.setAutoCommit(false);
            try {
                try (Statement settings = connection.createStatement()) {
                    settings.setQueryTimeout(3);
                    settings.execute("SET LOCAL statement_timeout = '3000ms'");
                    settings.execute("SET LOCAL lock_timeout = '1000ms'");
                    settings.execute("SET LOCAL search_path = pg_catalog, public");
                }
                validateRole(connection);
                try (Statement statement = connection.createStatement()) {
                    statement.setQueryTimeout(3);
                    statement.setMaxRows(500);
                    statement.setFetchSize(100);
                    try (var rows = statement.executeQuery(sql)) {
                        var metadata = rows.getMetaData();
                        int count = metadata.getColumnCount();
                        if (count < 1 || count > 16) { throw new SQLException("Result width exceeded"); }
                        List<String> columns = new ArrayList<>();
                        for (int i = 1; i <= count; i++) { columns.add(metadata.getColumnLabel(i)); }
                        List<List<Object>> values = new ArrayList<>();
                        while (rows.next() && values.size() < 500) {
                            List<Object> row = new ArrayList<>();
                            for (int i = 1; i <= count; i++) {
                                Object value = rows.getObject(i);
                                if (value instanceof java.sql.Date date) { value = date.toLocalDate().toString(); }
                                if (value != null && !(value instanceof Number) && !(value instanceof Boolean)) {
                                    value = value.toString();
                                    if (((String) value).length() > 128) { throw new SQLException("Cell bound exceeded"); }
                                }
                                row.add(value);
                            }
                            values.add(row);
                        }
                        return new ReportResult(columns, values);
                    }
                }
            } finally {
                // Never commit reporting transactions, even when a future implementation accidentally writes.
                connection.rollback();
            }
        } catch (BusinessRuleException e) {
            throw e;
        } catch (SQLException | RuntimeException e) {
            throw new BusinessRuleException("No se pudo ejecutar el reporte de forma segura");
        }
    }

    private void validateRole(Connection connection) throws SQLException {
        // Check effective permissions, not just flags or the JDBC hint. Public/inherited grants also count.
        String check = """
                SELECT current_user = ? AND current_setting('transaction_read_only') = 'on'
                  AND NOT (r.rolsuper OR r.rolcreatedb OR r.rolcreaterole OR r.rolreplication OR r.rolbypassrls)
                  AND NOT EXISTS (SELECT 1 FROM pg_auth_members WHERE member = r.oid)
                  AND NOT EXISTS (SELECT 1 FROM pg_database WHERE datdba = r.oid)
                  AND NOT EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner = r.oid)
                  AND NOT EXISTS (
                    SELECT 1 FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
                    WHERE n.nspname NOT LIKE 'pg_%' AND n.nspname <> 'information_schema'
                      AND (c.relowner = r.oid
                        OR (c.relkind IN ('r','p','v','m','f') AND
                          (has_table_privilege(current_user, c.oid, 'INSERT,UPDATE,DELETE,TRUNCATE,REFERENCES,TRIGGER')
                           OR (has_table_privilege(current_user, c.oid, 'SELECT') AND NOT
                             (n.nspname = 'public' AND c.relname IN ('report_income','report_orders','report_services') AND c.relkind = 'v'))))
                        OR (c.relkind = 'S' AND has_sequence_privilege(current_user, c.oid, 'USAGE,SELECT,UPDATE'))))
                  AND NOT has_database_privilege(current_user, current_database(), 'CREATE')
                  AND NOT has_schema_privilege(current_user, 'public', 'CREATE')
                FROM pg_roles r WHERE rolname = current_user
                """;
        try (var statement = connection.prepareStatement(check)) {
            statement.setString(1, username);
            statement.setQueryTimeout(3);
            try (var result = statement.executeQuery()) {
                if (!result.next() || !result.getBoolean(1)) { throw new SQLException("Reporting role is not restricted"); }
            }
        }
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (pool != null) { pool.close(); }
    }
}
