package com.dentalplatform.erp.localprofile;

import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.Map;

import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.ORIGIN;
import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.graphql;
import static com.dentalplatform.erp.localprofile.LocalProfileHttpSupport.preflight;
import static org.assertj.core.api.Assertions.assertThat;

/** Default (production) chain, no profile: CORS is off and X-Dev-User is just an unknown header. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProductionChainIsolationTest {

    @LocalServerPort int port;
    @MockitoBean RabbitTemplate rabbit;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl("postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> false);
        r.add("erp.catalog-outbox.enabled", () -> false);
    }

    @Test
    void devUserHeaderIsIgnoredWithoutJwt() throws Exception {
        for (String user : new String[]{"admin", "admin-scz"}) {
            var response = graphql(port, "{ listarUsuarios { username } }", Map.of("X-Dev-User", user));
            assertThat(response.statusCode()).isEqualTo(401);
            assertThat(response.body()).isEmpty();
        }
    }

    @Test
    void noCorsHeadersAreEmittedForAnyOrigin() throws Exception {
        var preflight = preflight(port, "/graphql", ORIGIN, "POST", "content-type,x-dev-user");
        assertThat(preflight.headers().map().keySet().stream().map(String::toLowerCase))
                .noneMatch(name -> name.startsWith("access-control-"));
        var actual = graphql(port, "{ listarRoles { codigo } }", Map.of("Origin", ORIGIN));
        assertThat(actual.statusCode()).isEqualTo(401);
        assertThat(actual.headers().map().keySet().stream().map(String::toLowerCase))
                .noneMatch(name -> name.startsWith("access-control-"));
    }
}
