package com.dentalplatform.erp.migration;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FlywayMigrationTest {

    private static DataSource dataSource;
    private static MigrateResult result;

    @BeforeAll
    static void migrate() throws SQLException {
        var postgres = EmbeddedPostgresSupport.postgres();
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS migration_test");
            s.execute("CREATE DATABASE migration_test");
        }
        dataSource = postgres.getDatabase("postgres", "migration_test");
        result = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }

    @Test
    void appliesSchemaAndSeedMigrations() {
        assertThat(result.success).isTrue();
        assertThat(result.migrations).extracting(m -> m.version).startsWith("1", "2");
    }

    @Test
    void createsEveryTableOfTheSpecification() throws SQLException {
        String[] tables = {"idempotency_keys", "empresa_config", "roles", "permisos", "rol_permisos", "usuarios",
                "audit_logs", "consultorios", "especialidades", "odontologos", "odontologo_especialidades",
                "pacientes", "servicios_odontologicos", "ordenes_cobro", "orden_cobro_items", "cuotas_pago",
                "transacciones_qr", "movimientos_caja", "recibos"};
        for (String table : tables) {
            assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_name = '" + table + "'"))
                    .as(table).isEqualTo(1);
        }
    }

    @Test
    void seedsSantaCruzCompanyInBolivianos() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT direccion, moneda_simbolo FROM empresa_config")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString("direccion")).contains("Santa Cruz de la Sierra");
            assertThat(rs.getString("moneda_simbolo")).isEqualTo("Bs");
        }
        assertThat(count("SELECT count(*) FROM pacientes")).isGreaterThanOrEqualTo(5);
        assertThat(count("SELECT count(*) FROM servicios_odontologicos")).isGreaterThanOrEqualTo(10);
    }

    @Test
    void seedsInstallmentsThatAddUpToTheOrderTotal() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT o.monto_total_bs, sum(q.monto_cuota_bs) AS cuotas
                     FROM ordenes_cobro o JOIN cuotas_pago q ON q.orden_cobro_id = o.id
                     GROUP BY o.id, o.monto_total_bs""")) {
            int orders = 0;
            while (rs.next()) {
                orders++;
                BigDecimal total = rs.getBigDecimal("monto_total_bs");
                assertThat(rs.getBigDecimal("cuotas")).isEqualByComparingTo(total);
            }
            assertThat(orders).isEqualTo(2);
        }
    }

    @Test
    void seedsBcryptPasswordsCompatibleWithSpringSecurity() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT password_hash FROM usuarios WHERE username = 'admin'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).startsWith("$2a$10$");
        }
    }

    @Test
    void rejectsNegativeServicePrices() {
        assertThatThrownBy(() -> {
            try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
                s.execute("INSERT INTO servicios_odontologicos (codigo, nombre, precio_base_bs) VALUES ('X', 'X', -1)");
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("precio_base_bs");
    }

    private static long count(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
