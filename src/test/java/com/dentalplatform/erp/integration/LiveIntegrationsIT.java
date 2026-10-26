package com.dentalplatform.erp.integration;

import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaPayload;
import com.dentalplatform.erp.mastercatalog.application.command.CrearConsultorioCommand;
import com.dentalplatform.erp.mastercatalog.application.usecase.CrearConsultorioUseCase;
import com.dentalplatform.erp.mastercatalog.infrastructure.outbox.CatalogOutboxDispatcher;
import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import io.minio.*;
import org.testcontainers.images.builder.Transferable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Explicit Maven profile only. No disabledWithoutDocker/assumptions: engine failure is an error. Runs under the
 * {@code local} profile (seeded synthetic users; Bearer JWTs validated against the Keycloak container below).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class LiveIntegrationsIT {
    static final String USER = "test-" + UUID.randomUUID();
    static final String PASSWORD = UUID.randomUUID().toString();
    static final String BUCKET = "private-receipts";
    static final GenericContainer<?> RABBIT = new GenericContainer<>("rabbitmq:4.1.4-management")
            .withEnv("RABBITMQ_DEFAULT_USER", USER).withEnv("RABBITMQ_DEFAULT_PASS", PASSWORD)
            .withExposedPorts(5672).waitingFor(Wait.forLogMessage(".*Server startup complete.*\\n", 1))
            .withStartupTimeout(Duration.ofSeconds(120));
    // Same pinned image as docker-compose.yml; identities make anonymous access fail closed.
    static final String S3_IDENTITIES = "{\"identities\":[{\"name\":\"live\",\"credentials\":[{\"accessKey\":\"" + USER
            + "\",\"secretKey\":\"" + PASSWORD + "\"}],\"actions\":[\"Admin\",\"Read\",\"Write\",\"List\"]}]}";
    static final GenericContainer<?> S3 = new GenericContainer<>("chrislusf/seaweedfs:4.48")
            .withCopyToContainer(Transferable.of(S3_IDENTITIES), "/etc/seaweedfs/s3.json")
            .withCommand("server", "-s3", "-s3.config=/etc/seaweedfs/s3.json", "-dir=/data")
            .withExposedPorts(8333, 9333)
            .waitingFor(Wait.forHttp("/cluster/status").forPort(9333))
            .withStartupTimeout(Duration.ofSeconds(120));
    // Same pinned image and realm file as docker-compose.yml. Random bootstrap admin per run; bounded startup.
    static final String KEYCLOAK_ADMIN = "kc-" + UUID.randomUUID();
    static final String KEYCLOAK_ADMIN_PASSWORD = UUID.randomUUID().toString();
    static final String REALM = "dental-platform";
    static final GenericContainer<?> KEYCLOAK = new GenericContainer<>("quay.io/keycloak/keycloak:26.8.1")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", KEYCLOAK_ADMIN)
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", KEYCLOAK_ADMIN_PASSWORD)
            .withCopyFileToContainer(MountableFile.forHostPath("docker/keycloak/dental-platform-realm.json"),
                    "/opt/keycloak/data/import/dental-platform-realm.json")
            .withCommand("start-dev", "--import-realm")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/" + REALM + "/.well-known/openid-configuration").forPort(8080))
            .withStartupTimeout(Duration.ofSeconds(300));
    static boolean started;
    @LocalServerPort int port;
    @Autowired ReceiptStoragePort storage;
    @Autowired CrearConsultorioUseCase createOffice;
    @Autowired CatalogOutboxDispatcher dispatcher;
    @Autowired RabbitTemplate rabbit;
    @Autowired CachingConnectionFactory connection;
    @Autowired JdbcTemplate jdbc;
    @Autowired JsonMapper json;

    static synchronized void start() {
        if (started) return;
        try {
            RABBIT.start();
            S3.start();
            KEYCLOAK.start();
            started = true;
        } catch (RuntimeException | Error failure) {
            KEYCLOAK.close();
            S3.close();
            RABBIT.close();
            throw failure;
        }
    }

    static String issuer() {
        return "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080) + "/realms/" + REALM;
    }

    static String endpoint() { return "http://" + S3.getHost() + ":" + S3.getMappedPort(8333); }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        start();
        r.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl("postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("erp.security.local-dev-user.jwt-issuer-uri", LiveIntegrationsIT::issuer);
        r.add("erp.catalog-outbox.enabled", () -> false);
        r.add("erp.catalog-outbox.initial-backoff-ms", () -> 100);
        r.add("erp.catalog-outbox.max-backoff-ms", () -> 100);
        r.add("spring.rabbitmq.host", RABBIT::getHost);
        r.add("spring.rabbitmq.port", () -> RABBIT.getMappedPort(5672));
        r.add("spring.rabbitmq.username", () -> USER);
        r.add("spring.rabbitmq.password", () -> PASSWORD);
        r.add("erp.receipts.mode", () -> "s3");
        r.add("erp.receipts.minio.endpoint", LiveIntegrationsIT::endpoint);
        r.add("erp.receipts.minio.access-key", () -> USER);
        r.add("erp.receipts.minio.secret-key", () -> PASSWORD);
        r.add("erp.receipts.minio.bucket", () -> BUCKET);
    }

    @AfterAll static void close() {
        try { KEYCLOAK.close(); } finally { try { S3.close(); } finally { RABBIT.close(); } }
    }

    @Test void realPrivateS3UploadAuthenticatedGetAndPresignedRead() throws Exception {
        // Operator provisioning, not an application-side auto-create or policy change.
        try (var client = MinioClient.builder().endpoint(endpoint()).credentials(USER, PASSWORD).region("us-east-1").build()) {
            client.makeBucket(MakeBucketArgs.builder().bucket(BUCKET).build());
            UUID id = UUID.randomUUID();
            byte[] pdf = "%PDF-1.7\nSynthetic integration artifact\n%%EOF".getBytes(StandardCharsets.US_ASCII);
            String reference = storage.guardar(id, pdf);
            assertThat(reference).isEqualTo("s3://" + BUCKET + "/" + id + ".pdf");
            try (var object = client.getObject(GetObjectArgs.builder().bucket(BUCKET).object(id + ".pdf").build())) {
                assertThat(object.readAllBytes()).isEqualTo(pdf);
            }
            var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            var signed = http.send(HttpRequest.newBuilder(URI.create(storage.resolverDescarga(id, reference)))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertThat(signed.statusCode()).isEqualTo(200);
            assertThat(signed.headers().firstValue("Content-Type")).contains("application/pdf");
            assertThat(signed.body()).isEqualTo(pdf);
            var anonymous = http.send(HttpRequest.newBuilder(URI.create(endpoint() + "/" + BUCKET + "/" + id + ".pdf"))
                    .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(anonymous.statusCode()).isEqualTo(403);
            assertThat(anonymous.body()).contains("AccessDenied").doesNotContain("Synthetic integration artifact");
        }
    }

    @Test void realBrokerOutageRetainsOutboxThenConfirmedRetryAndSagaDelivery() throws Exception {
        String queue = "test.catalog." + UUID.randomUUID();
        var office = createOffice.ejecutar(new CrearConsultorioCommand("LIVE-" + UUID.randomUUID().toString().substring(0, 8),
                "Synthetic live office", null));
        UUID eventId = jdbc.queryForObject("SELECT event_id FROM catalog_event_outbox WHERE payload->>'consultorioId'=?",
                UUID.class, office.getId().toString());
        String originalPayload = jdbc.queryForObject("SELECT payload::text FROM catalog_event_outbox WHERE event_id=?", String.class, eventId);
        controlBroker("stop_app");
        try {
            dispatcher.dispatch();
            assertThat(jdbc.queryForObject("SELECT attempts FROM catalog_event_outbox WHERE event_id=?", Integer.class, eventId)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT sent_at FROM catalog_event_outbox WHERE event_id=?", Object.class, eventId)).isNull();
        } finally {
            controlBroker("start_app");
            connection.resetConnection();
        }
        // Non-durable queues do not survive stop_app, so bind the probe queue only after the broker is back
        // and before the retry can publish; otherwise the confirmed message would be unroutable.
        var admin = new RabbitAdmin(connection);
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            admin.declareQueue(new org.springframework.amqp.core.Queue(queue, false, false, true));
            admin.declareBinding(new Binding(queue, Binding.DestinationType.QUEUE, "dental.topic.exchange",
                    "catalogo.consultorio.creado", null));
        });
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            dispatcher.dispatch();
            assertThat(jdbc.queryForObject("SELECT sent_at IS NOT NULL FROM catalog_event_outbox WHERE event_id=?", Boolean.class, eventId)).isTrue();
        });
        Message delivered = rabbit.receive(queue, 5000);
        assertThat(delivered).isNotNull();
        assertThat(delivered.getMessageProperties().getMessageId()).isEqualTo(eventId.toString());
        assertThat(delivered.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(json.readTree(delivered.getBody())).isEqualTo(json.readTree(originalPayload));
        assertThat(jdbc.queryForObject("SELECT attempts FROM catalog_event_outbox WHERE event_id=?", Integer.class, eventId)).isGreaterThanOrEqualTo(2);

        UUID consultation = UUID.randomUUID();
        var event = new ConsultaFinalizadaPayload(UUID.randomUUID(), consultation, UUID.randomUUID(),
                UUID.fromString("60000000-0000-0000-0000-000000000001"),
                UUID.fromString("50000000-0000-0000-0000-000000000001"),
                List.of(new ConsultaFinalizadaPayload.ServicioRealizado(
                        UUID.fromString("70000000-0000-0000-0000-000000000001"), "36", 2, new BigDecimal("125"))),
                "Synthetic saga delivery");
        rabbit.convertAndSend("dental.topic.exchange", "clinica.consulta.finalizada", event);
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            // queryForMap throws on zero rows, which Awaitility would not retry: poll with a list instead.
            var rows = jdbc.queryForList("SELECT estado, monto_total_bs FROM ordenes_cobro WHERE consulta_externa_id=?", consultation);
            assertThat(rows).hasSize(1);
            var row = rows.getFirst();
            assertThat(row.get("estado")).isEqualTo("PENDIENTE");
            assertThat((BigDecimal) row.get("monto_total_bs")).isEqualByComparingTo("250");
        });
    }

    // --- Keycloak: real tokens from the imported realm against the running MS1 (profile local) ---------------------

    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** Token endpoint call: returns {@code status:body}. */
    String tokenRequest(String realm, String body) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(issuer().replace("/realms/" + REALM, "/realms/" + realm)
                        + "/protocol/openid-connect/token")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        return response.statusCode() + ":" + response.body();
    }

    String passwordGrant(String client, String username) throws Exception {
        String result = tokenRequest(REALM, "grant_type=password&client_id=" + client + "&username=" + username
                + "&password=LocalDev2026!&scope=openid");
        assertThat(result).startsWith("200:");
        return json.readTree(result.substring(4)).path("access_token").asString();
    }

    HttpResponse<String> bearerGraphql(String token, String query) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/graphql"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("query", query)))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test void keycloakTokensCarryRolesAndDriveServerSideAuthorization() throws Exception {
        String query = "{ listarUsuarios { username } }";
        String admin = passwordGrant("ms1-local-tests", "admin-scz");
        String cashier = passwordGrant("ms1-local-tests", "caja-scz");
        String[] parts = admin.split("\\.");
        var claims = json.readTree(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
        assertThat(claims.path("preferred_username").asString()).isEqualTo("admin-scz");
        assertThat(claims.path("realm_access").path("roles").toString()).contains("\"ADMIN\"");
        assertThat(claims.path("aud").toString()).contains("ms-erp");

        var ok = bearerGraphql(admin, query);
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(ok.body()).contains("admin-scz", "caja-scz").doesNotContain("errors");

        var denied = bearerGraphql(cashier, query);
        assertThat(denied.statusCode()).isEqualTo(200);
        assertThat(denied.body()).contains("FORBIDDEN").doesNotContain("admin-scz");

        char original = parts[2].charAt(20);
        String tampered = parts[0] + "." + parts[1] + "." + parts[2].substring(0, 20) + (original == 'A' ? 'B' : 'A')
                + parts[2].substring(21);
        var rejected = bearerGraphql(tampered, query);
        assertThat(rejected.statusCode()).isEqualTo(401);
        assertThat(rejected.body()).isEmpty();
    }

    @Test void mfErpClientIsPublicPkceS256WithoutDirectGrants() throws Exception {
        String grant = tokenRequest("master", "grant_type=password&client_id=admin-cli&username=" + KEYCLOAK_ADMIN
                + "&password=" + KEYCLOAK_ADMIN_PASSWORD);
        assertThat(grant).startsWith("200:");
        String adminToken = json.readTree(grant.substring(4)).path("access_token").asString();
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(issuer().replace("/realms/", "/admin/realms/")
                        + "/clients?clientId=mf-erp")).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + adminToken).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        var client = json.readTree(response.body()).get(0);
        assertThat(client.path("publicClient").asBoolean()).isTrue();
        assertThat(client.path("standardFlowEnabled").asBoolean()).isTrue();
        assertThat(client.path("directAccessGrantsEnabled").asBoolean()).isFalse();
        assertThat(client.path("implicitFlowEnabled").asBoolean()).isFalse();
        assertThat(client.path("serviceAccountsEnabled").asBoolean()).isFalse();
        assertThat(client.path("attributes").path("pkce.code.challenge.method").asString()).isEqualTo("S256");
        assertThat(client.path("redirectUris").toString()).isEqualTo("[\"http://localhost:4201/*\"]");
        assertThat(client.path("webOrigins").toString()).isEqualTo("[\"http://localhost:4201\"]");
        // Behavioural proof: the password grant is refused for the frontend client.
        assertThat(tokenRequest(REALM, "grant_type=password&client_id=mf-erp&username=admin-scz&password=LocalDev2026!"))
                .startsWith("400:").contains("unauthorized_client");
    }

    static void controlBroker(String command) throws Exception {
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var pending = executor.submit(() -> RABBIT.execInContainer("rabbitmqctl", command));
        try {
            var result = pending.get(30, TimeUnit.SECONDS);
            assertThat(result.getExitCode()).as("rabbitmqctl %s", command).isZero();
        } finally {
            pending.cancel(true);
            executor.shutdownNow();
        }
    }
}
