package com.dentalplatform.erp.localprofile;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.graphql;
import static org.assertj.core.api.Assertions.assertThat;

/** The context starts with the configured issuer down; Bearer requests then fail with 401 until it comes up. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class LocalProfileJwtIssuerDownTest {

    private static final String DATABASE = "erp_local_jwt_down";
    private static final EmbeddedOidc OIDC = new EmbeddedOidc(EmbeddedOidc.freePort());

    @LocalServerPort int port;
    @MockitoBean RabbitTemplate rabbit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) throws Exception {
        try (Connection c = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();
             var s = c.createStatement()) {
            if (!s.executeQuery("SELECT 1 FROM pg_database WHERE datname='" + DATABASE + "'").next()) {
                s.execute("CREATE DATABASE " + DATABASE);
            }
        }
        r.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl(DATABASE));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        r.add("erp.catalog-outbox.enabled", () -> false);
        r.add("erp.security.local-dev-user.jwt-issuer-uri", () -> OIDC.issuer);
    }

    @AfterAll
    static void close() {
        OIDC.close();
    }

    @Test
    void startsWithIssuerDownAnswers401NotServerErrorAndRecoversWhenItComesUp() throws Exception {
        String query = "{ listarUsuarios { username } }";
        String token = OIDC.token(UUID.randomUUID().toString(), "admin-scz", "ADMIN");
        for (int attempt = 0; attempt < 2; attempt++) {
            var down = graphql(port, query, Map.of("Authorization", "Bearer " + token));
            assertThat(down.statusCode()).isEqualTo(401);
            assertThat(down.body()).isEmpty();
        }
        // X-Dev-User does not depend on the issuer.
        assertThat(graphql(port, query, Map.of("X-Dev-User", "admin-scz")).body()).contains("admin-scz");

        OIDC.start();
        var up = graphql(port, query, Map.of("Authorization", "Bearer " + token));
        assertThat(up.statusCode()).isEqualTo(200);
        assertThat(up.body()).contains("admin-scz").doesNotContain("errors");
    }
}
