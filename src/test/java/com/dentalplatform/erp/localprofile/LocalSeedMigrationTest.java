package com.dentalplatform.erp.localprofile;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Applies the real Flyway locations on a fresh PostgreSQL 18 database: first production's (db/migration only), then
 * the local profile's (db/migration + db/local) and proves the synthetic seed is complete, coherent and idempotent.
 */
class LocalSeedMigrationTest {

    private static final String DB = "local_seed_it";
    private static final String LOCAL_ID = "id::text ~ '^a[0-9a-b]000000-0000-0000-0000-'";
    private static final Path SEED_SQL = Path.of("src/main/resources/db/local/R__local_integration_seed.sql");
    private static final String[] COUNTED_TABLES = {"empresa_config", "roles", "permisos", "rol_permisos", "usuarios",
            "especialidades", "odontologos", "odontologo_especialidades", "consultorios", "pacientes",
            "servicios_odontologicos", "ordenes_cobro", "orden_cobro_items", "cuotas_pago", "transacciones_qr",
            "movimientos_caja", "recibos", "audit_logs", "idempotency_keys", "catalog_event_outbox"};
    private static final String[] SEEDED_TABLES = {"usuarios", "especialidades", "odontologos", "consultorios",
            "pacientes", "servicios_odontologicos", "ordenes_cobro", "orden_cobro_items", "cuotas_pago",
            "movimientos_caja", "recibos"};
    /** Tables that hold V2 rows; filter selecting the rows that do NOT belong to the local seed. */
    private static final Map<String, String> V2_ROWS = Map.ofEntries(
            Map.entry("empresa_config", "TRUE"), Map.entry("roles", "TRUE"), Map.entry("permisos", "TRUE"),
            Map.entry("rol_permisos", "TRUE"), Map.entry("usuarios", "NOT (" + LOCAL_ID + ")"),
            Map.entry("especialidades", "NOT (" + LOCAL_ID + ")"), Map.entry("odontologos", "NOT (" + LOCAL_ID + ")"),
            Map.entry("odontologo_especialidades", "odontologo_id::text !~ '^a3'"),
            Map.entry("consultorios", "NOT (" + LOCAL_ID + ")"), Map.entry("pacientes", "NOT (" + LOCAL_ID + ")"),
            Map.entry("servicios_odontologicos", "NOT (" + LOCAL_ID + ")"),
            Map.entry("ordenes_cobro", "NOT (" + LOCAL_ID + ")"), Map.entry("orden_cobro_items", "NOT (" + LOCAL_ID + ")"),
            Map.entry("cuotas_pago", "NOT (" + LOCAL_ID + ")"));

    private static DataSource dataSource;
    private static Map<String, String> v2Before;
    private static long localRowsInProductionChain;
    private static int productionRepeatables;

    @BeforeAll
    static void migrate() throws SQLException {
        var postgres = EmbeddedPostgresSupport.postgres();
        try (Connection c = postgres.getPostgresDatabase().getConnection(); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + DB);
            s.execute("CREATE DATABASE " + DB);
        }
        dataSource = postgres.getDatabase("postgres", DB);

        // Production chain: default location only.
        var production = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        production.migrate();
        productionRepeatables = (int) java.util.Arrays.stream(production.info().all())
                .filter(i -> i.getVersion() == null).count();
        localRowsInProductionChain = scalar("SELECT (SELECT count(*) FROM usuarios WHERE " + LOCAL_ID + ")"
                + " + (SELECT count(*) FROM pacientes WHERE " + LOCAL_ID + ")"
                + " + (SELECT count(*) FROM servicios_odontologicos WHERE " + LOCAL_ID + ")"
                + " + (SELECT count(*) FROM movimientos_caja WHERE " + LOCAL_ID + ")");
        v2Before = v2Snapshot();

        // Local chain on the same database: adds the seed on top of V1..V6.
        localFlyway().migrate();
    }

    private static Flyway localFlyway() {
        return Flyway.configure().dataSource(dataSource)
                .locations("classpath:db/migration", "classpath:db/local").load();
    }

    @Test
    void productionLocationAloneNeverContainsTheSeed() throws SQLException {
        assertThat(productionRepeatables).isZero();
        assertThat(localRowsInProductionChain).isZero();
        assertThat(scalar("SELECT count(*) FROM usuarios WHERE username = 'admin-scz'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM flyway_schema_history WHERE script LIKE 'R__local%' AND success"))
                .isPositive();
    }

    @Test
    void localAdminAndRoleUsersExistWithBcryptHashes() throws SQLException {
        Map<String, String> roles = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT u.username, r.codigo, u.activo, u.password_hash,
                            u.password_hash = crypt('LocalDev2026!', u.password_hash) AS password_ok
                     FROM usuarios u JOIN roles r ON r.id = u.rol_id
                     WHERE u.username IN ('admin-scz', 'caja-scz', 'recepcion-scz')""")) {
            while (rs.next()) {
                roles.put(rs.getString("username"), rs.getString("codigo"));
                assertThat(rs.getBoolean("activo")).isTrue();
                assertThat(rs.getString("password_hash")).startsWith("$2a$");
                assertThat(rs.getBoolean("password_ok")).isTrue();
            }
        }
        assertThat(roles).containsEntry("admin-scz", "ADMIN").containsEntry("caja-scz", "CAJERO")
                .containsEntry("recepcion-scz", "RECEPCIONISTA").hasSize(3);
    }

    @Test
    void doesNotCreateASecondCompanyAndHasCatalogs() throws SQLException {
        assertThat(scalar("SELECT count(*) FROM empresa_config")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM especialidades WHERE " + LOCAL_ID)).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM odontologos WHERE " + LOCAL_ID)).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM odontologo_especialidades WHERE odontologo_id::text ~ '^a3'"))
                .isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM consultorios WHERE " + LOCAL_ID)).isEqualTo(2);
        assertThat(scalar("SELECT count(*) FROM pacientes WHERE " + LOCAL_ID + " AND ci_nit LIKE 'LOCAL-%'"))
                .isEqualTo(5);
    }

    @Test
    void requiredServicesExistWithExactNamesAndBsScaleTwo() throws SQLException {
        Map<String, BigDecimal> prices = new LinkedHashMap<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT s.nombre, s.precio_base_bs, e.id AS especialidad
                     FROM servicios_odontologicos s JOIN especialidades e ON e.id = s.especialidad_id
                     WHERE s.nombre IN ('Exodoncia de Tercer Molar Retenido', 'Restauración Fotopolimerizable con Resina')""")) {
            while (rs.next()) {
                prices.put(rs.getString("nombre"), rs.getBigDecimal("precio_base_bs"));
            }
        }
        assertThat(prices).containsOnlyKeys("Exodoncia de Tercer Molar Retenido",
                "Restauración Fotopolimerizable con Resina");
        assertThat(prices.values()).allSatisfy(price -> assertThat(price.scale()).isEqualTo(2));
        assertThat(prices.get("Exodoncia de Tercer Molar Retenido")).isEqualByComparingTo("1250.00");
        assertThat(prices.get("Restauración Fotopolimerizable con Resina")).isEqualByComparingTo("280.00");
    }

    @Test
    void ordersAreArithmeticallyCoherent() throws SQLException {
        int orders = 0;
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT o.id, o.estado, o.monto_subtotal_bs, o.descuento_bs, o.monto_total_bs, o.saldo_pendiente_bs,
                            o.cantidad_cuotas,
                            (SELECT sum(subtotal_bs) FROM orden_cobro_items i WHERE i.orden_cobro_id = o.id) AS items,
                            count(q.id) AS cuotas, sum(q.monto_cuota_bs) AS suma,
                            coalesce(sum(q.monto_cuota_bs) FILTER (WHERE q.estado = 'PAGADA'), 0) AS pagado,
                            count(*) FILTER (WHERE q.estado = 'PAGADA') AS pagadas
                     FROM ordenes_cobro o JOIN cuotas_pago q ON q.orden_cobro_id = o.id
                     WHERE o.id::text ~ '^a7000000-'
                     GROUP BY o.id""")) {
            while (rs.next()) {
                orders++;
                String id = rs.getString("id");
                assertThat(rs.getBigDecimal("items")).as(id).isEqualByComparingTo(rs.getBigDecimal("monto_subtotal_bs"));
                assertThat(rs.getBigDecimal("monto_subtotal_bs").subtract(rs.getBigDecimal("descuento_bs")))
                        .as(id).isEqualByComparingTo(rs.getBigDecimal("monto_total_bs"));
                assertThat(rs.getBigDecimal("suma")).as(id).isEqualByComparingTo(rs.getBigDecimal("monto_total_bs"));
                assertThat(rs.getBigDecimal("monto_total_bs").subtract(rs.getBigDecimal("pagado")))
                        .as(id).isEqualByComparingTo(rs.getBigDecimal("saldo_pendiente_bs"));
                assertThat(rs.getInt("cuotas")).as(id).isEqualTo(rs.getInt("cantidad_cuotas"));
                String expected = rs.getInt("pagadas") == 0 ? "PENDIENTE" : "PARCIAL";
                assertThat(rs.getString("estado")).as(id).isEqualTo(expected);
            }
        }
        assertThat(orders).isEqualTo(4);
    }

    @Test
    void creditPlanKeepsTheRemainderCentOnTheLastInstallment() throws SQLException {
        List<BigDecimal> amounts = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT monto_cuota_bs FROM cuotas_pago
                     WHERE orden_cobro_id = 'a7000000-0000-0000-0000-000000000002' AND estado = 'PENDIENTE'
                     ORDER BY numero_cuota""")) {
            while (rs.next()) {
                amounts.add(rs.getBigDecimal(1));
            }
        }
        assertThat(amounts).containsExactly(new BigDecimal("333.33"), new BigDecimal("333.33"), new BigDecimal("333.34"));
        assertThat(scalar("SELECT count(*) FROM cuotas_pago WHERE orden_cobro_id = 'a7000000-0000-0000-0000-000000000001'"
                + " AND estado = 'PENDIENTE'")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM cuotas_pago WHERE orden_cobro_id = 'a7000000-0000-0000-0000-000000000003'"
                + " AND estado = 'PENDIENTE'")).isEqualTo(1);
    }

    @Test
    void paidInstallmentHasCoherentCashMovementAndNonCollidingReceipt() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("""
                     SELECT q.monto_cuota_bs, q.fecha_pago, m.tipo_movimiento, m.metodo_pago, m.monto_bs,
                            r.numero_recibo, r.monto_total_bs
                     FROM cuotas_pago q JOIN movimientos_caja m ON m.cuota_id = q.id JOIN recibos r ON r.movimiento_caja_id = m.id
                     WHERE q.estado = 'PAGADA' AND q.id::text ~ '^a9000000-'""")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getTimestamp("fecha_pago")).isNotNull();
            assertThat(rs.getString("tipo_movimiento")).isEqualTo("INGRESO");
            assertThat(rs.getBigDecimal("monto_bs")).isEqualByComparingTo(rs.getBigDecimal("monto_cuota_bs"));
            assertThat(rs.getBigDecimal("monto_total_bs")).isEqualByComparingTo(rs.getBigDecimal("monto_cuota_bs"));
            assertThat(rs.getString("numero_recibo")).isEqualTo("LOCAL-REC-000001")
                    .doesNotMatch("REC-\\d{4}-\\d{6}");
            assertThat(rs.next()).isFalse();
        }
        // The runtime sequence is untouched: the first real receipt is still REC-<year>-000001.
        assertThat(scalar("SELECT CASE WHEN is_called THEN 1 ELSE 0 END FROM recibo_numero_seq")).isZero();
    }

    @Test
    void seededMovementsAreVisibleThroughReportIncomeForThisAndLastWeek() throws SQLException {
        String thisWeek = "day >= date_trunc('week', now() AT TIME ZONE 'America/La_Paz')::date";
        String lastWeek = "day < date_trunc('week', now() AT TIME ZONE 'America/La_Paz')::date"
                + " AND day >= date_trunc('week', now() AT TIME ZONE 'America/La_Paz')::date - 7";
        assertThat(scalar("SELECT count(*) FROM report_income WHERE " + thisWeek + " AND payment_method = 'EFECTIVO'"))
                .isPositive();
        assertThat(scalar("SELECT count(*) FROM report_income WHERE " + thisWeek + " AND payment_method = 'QR_PAGOFACIL'"))
                .isPositive();
        assertThat(scalar("SELECT count(*) FROM report_income WHERE " + lastWeek)).isPositive();
        assertThat(scalar("SELECT count(*) FROM movimientos_caja WHERE tipo_movimiento = 'EGRESO' AND "
                + "fecha_movimiento >= date_trunc('week', now() AT TIME ZONE 'America/La_Paz') AT TIME ZONE 'America/La_Paz'"))
                .isPositive();
        assertThat(scalar("SELECT count(*) FROM movimientos_caja WHERE fecha_movimiento > now() + interval '1 hour'"))
                .isZero();
    }

    @Test
    void v2SeedRowsAreNotModified() throws SQLException {
        assertThat(v2Snapshot()).isEqualTo(v2Before);
        assertThat(v2Before.get("usuarios")).isNotNull();
    }

    @Test
    void rerunningFlywayAndTheSeedSqlDirectlyChangesNothing() throws SQLException, IOException {
        Map<String, String> before = fullSnapshot();

        var flyway = localFlyway();
        flyway.repair();
        flyway.migrate();
        assertThat(fullSnapshot()).as("repair+migrate").isEqualTo(before);

        // Force Flyway to re-apply the repeatable migration as if its checksum had changed.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute("UPDATE flyway_schema_history SET checksum = 0 WHERE script LIKE 'R__local%'");
        }
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(fullSnapshot()).as("repeatable re-applied by Flyway").isEqualTo(before);

        String sql = Files.readString(SEED_SQL, StandardCharsets.UTF_8);
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.execute(sql);
            s.execute(sql);
        }
        assertThat(fullSnapshot()).as("seed SQL executed twice directly").isEqualTo(before);
        assertThat(before.get("usuarios")).contains("count=");
    }

    /** Per-table row count for every table, plus an md5 of the seeded rows (content, not just cardinality). */
    private static Map<String, String> fullSnapshot() throws SQLException {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (String table : COUNTED_TABLES) {
            snapshot.put(table, "count=" + scalar("SELECT count(*) FROM " + table));
        }
        for (String table : SEEDED_TABLES) {
            snapshot.put(table, snapshot.get(table) + " md5=" + text("SELECT coalesce(md5(string_agg(t::text, '|' ORDER BY t.id)), '') FROM "
                    + table + " t WHERE " + LOCAL_ID.replaceAll("\\bid::text", "t.id::text")));
        }
        return snapshot;
    }

    private static Map<String, String> v2Snapshot() throws SQLException {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (var entry : V2_ROWS.entrySet()) {
            String sql = "SELECT count(*) || ':' || coalesce(md5(string_agg(t::text, '|' ORDER BY t::text)), '') FROM "
                    + entry.getKey() + " t WHERE " + entry.getValue().replaceAll("\\bid::text", "t.id::text");
            snapshot.put(entry.getKey(), text(sql));
        }
        return snapshot;
    }

    private static long scalar(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static String text(String sql) throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}
