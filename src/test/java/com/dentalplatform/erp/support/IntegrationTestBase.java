package com.dentalplatform.erp.support;

import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Base for integration tests: full Spring context on a real PostgreSQL (embedded, shared per JVM) with Flyway
 * applied, dev security mode and RabbitMQ listeners disabled. Keep the configuration identical across subclasses
 * so the Spring context is cached; tests share one database, so use unique values per test.
 */
@SpringBootTest
public abstract class IntegrationTestBase {

    private static final String DATABASE = "erp_it";

    @Autowired
    protected ExecutionGraphQlService graphQlService;

    @Autowired
    protected JdbcTemplate jdbc;

    protected ExecutionGraphQlServiceTester graphQl;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        createDatabaseIfAbsent();
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl(DATABASE));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("erp.security.dev-mode", () -> "true");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
    }

    @BeforeEach
    void createGraphQlTester() {
        // Calling the service directly bypasses the web layer that normally propagates the SecurityContext
        // (set by @WithMockUser on the test thread) into the GraphQL execution, so capture it here.
        ExecutionGraphQlService withSecurityContext = request -> graphQlService.execute(request)
                .contextWrite(ctx -> ContextSnapshotFactory.builder().build().captureAll().updateContext(ctx));
        graphQl = ExecutionGraphQlServiceTester.create(withSecurityContext);
    }

    private static synchronized void createDatabaseIfAbsent() {
        try (Connection c = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();
             Statement s = c.createStatement()) {
            var exists = s.executeQuery("SELECT 1 FROM pg_database WHERE datname = '" + DATABASE + "'");
            if (!exists.next()) {
                s.execute("CREATE DATABASE " + DATABASE);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare test database " + DATABASE, e);
        }
    }
}
