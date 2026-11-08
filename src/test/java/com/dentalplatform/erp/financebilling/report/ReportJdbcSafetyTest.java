package com.dentalplatform.erp.financebilling.report;

import com.dentalplatform.erp.financebilling.infrastructure.report.ReportingJdbcQueryAdapter;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class ReportJdbcSafetyTest {
    @Test
    void jdbcTransactionAndStatementHaveIndependentBoundsEvenIfDriverIgnoresRowLimit() throws Exception {
        var pool = org.mockito.Mockito.mock(com.zaxxer.hikari.HikariDataSource.class);
        var connection = org.mockito.Mockito.mock(java.sql.Connection.class);
        var statement = org.mockito.Mockito.mock(java.sql.Statement.class);
        var roleStatement = org.mockito.Mockito.mock(java.sql.PreparedStatement.class);
        var role = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        var rows = org.mockito.Mockito.mock(java.sql.ResultSet.class);
        var metadata = org.mockito.Mockito.mock(java.sql.ResultSetMetaData.class);
        org.mockito.Mockito.when(pool.getConnection()).thenReturn(connection);
        org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
        org.mockito.Mockito.when(connection.prepareStatement(org.mockito.ArgumentMatchers.anyString())).thenReturn(roleStatement);
        org.mockito.Mockito.when(roleStatement.executeQuery()).thenReturn(role);
        org.mockito.Mockito.when(role.next()).thenReturn(true);
        org.mockito.Mockito.when(role.getBoolean(1)).thenReturn(true);
        org.mockito.Mockito.when(statement.executeQuery("SELECT * FROM report_services LIMIT 500")).thenReturn(rows);
        org.mockito.Mockito.when(rows.getMetaData()).thenReturn(metadata);
        org.mockito.Mockito.when(metadata.getColumnCount()).thenReturn(1);
        org.mockito.Mockito.when(metadata.getColumnLabel(1)).thenReturn("code");
        org.mockito.Mockito.when(rows.next()).thenReturn(true);
        org.mockito.Mockito.when(rows.getObject(1)).thenReturn("SAFE");
        try (var adapter = new ReportingJdbcQueryAdapter("jdbc:postgresql://localhost/test", "reports", "test", "app")) {
            org.springframework.test.util.ReflectionTestUtils.setField(adapter, "pool", pool);
            assertThat(adapter.execute("SELECT * FROM report_services").rows()).hasSize(500);
            org.mockito.Mockito.verify(connection).setReadOnly(true);
            org.mockito.Mockito.verify(connection).setAutoCommit(false);
            org.mockito.Mockito.verify(connection).rollback();
            org.mockito.Mockito.verify(connection, org.mockito.Mockito.never()).commit();
            org.mockito.Mockito.verify(statement, org.mockito.Mockito.times(2)).setQueryTimeout(3);
            org.mockito.Mockito.verify(roleStatement).setQueryTimeout(3);
            org.mockito.Mockito.verify(statement).setMaxRows(500);
            org.mockito.Mockito.verify(statement).executeQuery("SELECT * FROM report_services LIMIT 500");
            org.mockito.Mockito.verify(statement).execute("SET LOCAL statement_timeout = '3000ms'");
            org.mockito.Mockito.verify(statement).execute("SET LOCAL lock_timeout = '1000ms'");
        }
    }

    @Test
    void jdbcUrlCannotOverrideIndependentCredentials() {
        try (var adapter = new ReportingJdbcQueryAdapter("jdbc:postgresql://localhost/missing?user=app", "reports", "test", "app")) {
            assertThatThrownBy(() -> adapter.execute("SELECT * FROM report_services"))
                    .isInstanceOf(BusinessRuleException.class)
                    .hasMessage("Reportes no disponibles: configure credenciales de solo lectura independientes");
        }
    }

    @Test
    void missingAndSharedCredentialsFailOnlyReportsWithoutOpeningDatabase() {
        for (String user : new String[]{"", "app"}) {
            try (var adapter = new ReportingJdbcQueryAdapter("jdbc:postgresql://localhost/missing", user, "password", "app")) {
                assertThatThrownBy(() -> adapter.execute("SELECT * FROM report_services"))
                        .isInstanceOf(BusinessRuleException.class)
                        .hasMessage("Reportes no disponibles: configure credenciales de solo lectura independientes");
            }
        }
        try (var adapter = new ReportingJdbcQueryAdapter("jdbc:postgresql://localhost/missing", "reports", "", "app")) {
            assertThatThrownBy(() -> adapter.execute("SELECT * FROM report_services"))
                    .isInstanceOf(BusinessRuleException.class);
        }
    }
}
