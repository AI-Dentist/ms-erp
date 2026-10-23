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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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

/** Explicit Maven profile only. No disabledWithoutDocker/assumptions: engine failure is an error. */
@SpringBootTest
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
    static boolean started;
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
            started = true;
        } catch (RuntimeException | Error failure) {
            S3.close();
            RABBIT.close();
            throw failure;
        }
    }

    static String endpoint() { return "http://" + S3.getHost() + ":" + S3.getMappedPort(8333); }

    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        start();
        r.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl("postgres"));
        r.add("spring.datasource.username", () -> "postgres");
        r.add("spring.datasource.password", () -> "postgres");
        r.add("erp.security.dev-mode", () -> true);
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
        try { S3.close(); } finally { RABBIT.close(); }
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
