package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.usecase.FacturacionSagaUseCase;
import com.dentalplatform.erp.support.EmbeddedPostgresSupport;
import io.micrometer.context.ContextSnapshotFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.graphql.ExecutionGraphQlService;
import org.springframework.graphql.test.tester.ExecutionGraphQlServiceTester;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest
@Import(BillingRejectionOutboxIntegrationTest.TimeConfiguration.class)
@WithMockUser(username = "admin", roles = "ADMIN")
class BillingRejectionOutboxIntegrationTest {
    private static final String DATABASE = "billing_outbox_" + UUID.randomUUID().toString().replace("-", "");
    private static final String REJECTED = "FACTURACION_CONSULTA_RECHAZADA";
    private static final String FAILED = "FACTURACION_RECHAZADA_PUBLICACION_FALLIDA";
    private static final String EXCHANGE = "dental.topic.exchange";
    private static final String ROUTING_KEY = "facturacion.consulta.rechazada";
    private static final String UNSAFE = "synthetic-private-observation patient@example.invalid";
    private static boolean databaseCreated;

    @Autowired ApplicationContext context;
    @Autowired FacturacionSagaUseCase saga;
    @Autowired ExecutionGraphQlService graphQlService;
    @Autowired JsonMapper json;
    @Autowired MutableClock clock;
    @MockitoBean RabbitTemplate rabbit;
    private ExecutionGraphQlServiceTester graphQl;
    private final List<Frame> frames = new CopyOnWriteArrayList<>();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        createDatabase();
        registry.add("spring.datasource.url", () -> EmbeddedPostgresSupport.jdbcUrl(DATABASE));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
        registry.add("erp.security.dev-mode", () -> "true");
        registry.add("erp.catalog-outbox.enabled", () -> "false");
        registry.add("erp.billing-saga.outbox.enabled", () -> "false");
        registry.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        registry.add("spring.rabbitmq.listener.direct.auto-startup", () -> "false");
        registry.add("erp.billing-saga.outbox.max-attempts", () -> "3");
        registry.add("erp.billing-saga.outbox.batch-size", () -> "1");
        registry.add("erp.billing-saga.outbox.send-timeout-ms", () -> "500");
        registry.add("erp.billing-saga.outbox.initial-backoff-ms", () -> "10");
        registry.add("erp.billing-saga.outbox.max-backoff-ms", () -> "20");
    }

    private static synchronized void createDatabase() {
        if (databaseCreated) return;
        try (var connection = EmbeddedPostgresSupport.postgres().getPostgresDatabase().getConnection();
             var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + DATABASE); // Setup only; observations are public GraphQL/AMQP.
            databaseCreated = true;
        } catch (SQLException e) {
            throw new IllegalStateException("Could not prepare isolated billing outbox database", e);
        }
    }

    @BeforeEach
    void createGraphQlTester() {
        ExecutionGraphQlService secured = request -> graphQlService.execute(request)
                .contextWrite(ctx -> ContextSnapshotFactory.builder().build().captureAll().updateContext(ctx));
        graphQl = ExecutionGraphQlServiceTester.create(secured);
    }

    @Test
    void confirmedAckWithoutReturnPublishesExactlyOnce() {
        var rejection = enqueue();
        broker(Outcome.ACK);
        dispatch();
        assertFrames(rejection, 1);
        clock.advance(Duration.ofDays(1));
        dispatch();
        assertFrames(rejection, 1);
        assertUnchanged(rejection);
        assertThat(failures(rejection)).isEmpty();
    }

    @Test
    void nackStopsAtThreeAttemptsWithExponentialCappedDueTimes() {
        assertTerminal(Outcome.NACK);
    }

    @Test
    void ackWithReturnedMessageIsNotDeliveryAndEventuallyFails() {
        assertTerminal(Outcome.RETURN);
    }

    @Test
    void missingConfirmTimesOutAndEventuallyFailsWithoutExtraAttempts() {
        assertTerminal(Outcome.TIMEOUT);
    }

    @Test
    void nackThenAckRetriesTheSameCompensationAndDoesNotFail() {
        var rejection = enqueue();
        broker(Outcome.NACK);
        dispatch();
        assertFrames(rejection, 1);
        dispatch();
        assertFrames(rejection, 1);
        clock.advance(Duration.ofMillis(10));
        broker(Outcome.ACK);
        dispatch();
        assertFrames(rejection, 2);
        clock.advance(Duration.ofDays(1));
        dispatch();
        assertFrames(rejection, 2);
        assertThat(failures(rejection)).isEmpty();
        assertUnchanged(rejection);
    }

    @Test
    void concurrentPublicDriversPublishTheSinglePendingRejectionOnce() throws Exception {
        var rejection = enqueue();
        broker(Outcome.ACK);
        Runnable driver = driver();
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Callable<Void> task = () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS), "Concurrent dispatch start was not released");
                driver.run();
                return null;
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS), "Both dispatchers must be ready");
                start.countDown();
                first.get(5, TimeUnit.SECONDS);
                second.get(5, TimeUnit.SECONDS);
            } finally {
                start.countDown();
                first.cancel(true);
                second.cancel(true);
            }
        }
        assertFrames(rejection, 1);
        clock.advance(Duration.ofDays(1));
        dispatch();
        assertFrames(rejection, 1);
        assertThat(failures(rejection)).isEmpty();
        assertUnchanged(rejection);
    }

    @Test
    void brokerConnectionFailureIsBoundedAndOperatorDetailIsSafe() {
        assertTerminal(Outcome.CONNECTION);
    }

    @Test
    void exceptionalBrokerConfirmationIsBoundedAndOperatorVisible() {
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> assertTerminal(Outcome.CONFIRM_FAILURE));
    }

    private void assertTerminal(Outcome outcome) {
        var rejection = enqueue();
        broker(outcome);
        dispatch();
        assertFrames(rejection, 1);
        assertThat(failures(rejection)).isEmpty();
        dispatch(); // Not due at zero elapsed time.
        assertFrames(rejection, 1);
        clock.advance(Duration.ofMillis(10));
        dispatch();
        assertFrames(rejection, 2);
        assertThat(failures(rejection)).isEmpty();
        clock.advance(Duration.ofMillis(19));
        dispatch();
        assertFrames(rejection, 2);
        clock.advance(Duration.ofMillis(1));
        dispatch();
        assertFrames(rejection, 3);
        var failure = failures(rejection);
        assertThat(failure).hasSize(1);
        assertThat(failure.getFirst()).containsEntry("entidadAfectada", "rechazos_facturacion")
                .containsEntry("entidadId", rejection.eventId());
        assertThat(detail(failure.getFirst())).containsEntry("consultaFinalizadaEventId", rejection.closure().toString())
                .containsEntry("attempts", 3).containsEntry("estado", "FALLIDO");
        assertThat(failure.getFirst().get("detalleJson").toString())
                .doesNotContain(UNSAFE, "AmqpConnectException", "java.net", "stackTrace");
        clock.advance(Duration.ofDays(1));
        dispatch();
        assertFrames(rejection, 3);
        assertThat(failures(rejection)).isEqualTo(failure);
        assertUnchanged(rejection);
    }

    private Rejection enqueue() {
        var before = orders();
        UUID consulta = UUID.randomUUID();
        UUID closure = UUID.randomUUID();
        saga.registrarRechazo(consulta, closure, "PAYLOAD_INVALIDO");
        var initial = audits().stream().filter(a -> REJECTED.equals(a.get("accion")))
                .filter(a -> consulta.toString().equals(detail(a).get("consultaId"))).toList();
        assertThat(initial).hasSize(1);
        assertThat(orders()).containsExactlyInAnyOrderElementsOf(before);
        return new Rejection(consulta, closure, initial.getFirst().get("entidadId").toString(), initial, before);
    }

    private Runnable driver() {
        assertTrue(context.containsBean("billingRejectionOutboxDispatcher"),
                "Missing public billingRejectionOutboxDispatcher Runnable");
        return context.getBean("billingRejectionOutboxDispatcher", Runnable.class);
    }

    private void dispatch() { driver().run(); }

    private void broker(Outcome outcome) {
        doAnswer(call -> {
            Message message = call.getArgument(2);
            CorrelationData correlation = call.getArgument(3);
            frames.add(new Frame(call.getArgument(0), call.getArgument(1), message, correlation.getId()));
            if (outcome == Outcome.CONNECTION) {
                throw new AmqpConnectException(new java.net.ConnectException(UNSAFE));
            }
            if (outcome == Outcome.RETURN) {
                correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", EXCHANGE, ROUTING_KEY));
            }
            if (outcome == Outcome.CONFIRM_FAILURE) {
                correlation.getFuture().completeExceptionally(
                        new AmqpConnectException(new java.net.ConnectException(UNSAFE)));
                return null;
            }
            if (outcome != Outcome.TIMEOUT) {
                correlation.getFuture().complete(new CorrelationData.Confirm(outcome != Outcome.NACK, UNSAFE));
            }
            return null;
        }).when(rabbit).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }

    @SuppressWarnings("unchecked")
    private void assertFrames(Rejection rejection, int count) {
        var own = frames.stream().filter(f -> rejection.eventId().equals(f.correlationId())).toList();
        assertThat(own).hasSize(count);
        assertThat(frames).hasSize(count); // This database has no other pending fixture rows.
        for (var frame : own) {
            assertThat(frame.exchange()).isEqualTo(EXCHANGE);
            assertThat(frame.routingKey()).isEqualTo(ROUTING_KEY);
            var properties = frame.message().getMessageProperties();
            assertThat(properties.getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
            assertThat(properties.getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(properties.getMessageId()).isEqualTo(rejection.eventId());
            assertThat(frame.message().getBody()).isEqualTo(own.getFirst().message().getBody());
            Map<String, Object> payload = json.readValue(
                    new String(frame.message().getBody(), StandardCharsets.UTF_8), Map.class);
            assertThat(payload).containsOnlyKeys("eventId", "consultaId", "consultaFinalizadaEventId",
                    "motivoCodigo", "motivo", "rechazadaEn");
            assertThat(payload).containsEntry("eventId", rejection.eventId())
                    .containsEntry("consultaId", rejection.consulta().toString())
                    .containsEntry("consultaFinalizadaEventId", rejection.closure().toString())
                    .containsEntry("motivoCodigo", "PAYLOAD_INVALIDO")
                    .containsEntry("motivo", "Los datos de facturación no son válidos.");
            assertThat(payload.get("motivo").toString().length()).isBetween(1, 500);
            assertThat(Instant.parse(payload.get("rechazadaEn").toString())).isNotNull();
        }
    }

    private void assertUnchanged(Rejection rejection) {
        assertThat(audits().stream().filter(a -> REJECTED.equals(a.get("accion")))
                .filter(a -> rejection.eventId().equals(a.get("entidadId"))).toList()).isEqualTo(rejection.initialAudit());
        assertThat(orders()).containsExactlyInAnyOrderElementsOf(rejection.ordersBefore());
    }

    private List<Map<String, Object>> failures(Rejection rejection) {
        return audits().stream().filter(a -> FAILED.equals(a.get("accion")))
                .filter(a -> rejection.eventId().equals(a.get("entidadId"))).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> audits() {
        return graphQl.document("{ listarAuditLogs(limite: 500) { id accion entidadAfectada entidadId detalleJson } }")
                .execute().path("listarAuditLogs").entityList(Map.class).get().stream()
                .map(a -> (Map<String, Object>) a).toList();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> orders() {
        return graphQl.document("""
                { listarOrdenesCobro { id consultaExternaId estado montoSubtotalBs descuentoBs
                  montoTotalBs saldoPendienteBs cantidadCuotas items { id cantidad precioUnitarioBs subtotalBs }
                  cuotas { id numeroCuota montoCuotaBs estado fechaVencimiento fechaPago } } }
                """).execute().path("listarOrdenesCobro").entityList(Map.class).get().stream()
                .map(a -> (Map<String, Object>) a).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(Map<String, Object> audit) {
        return json.readValue((String) audit.get("detalleJson"), Map.class);
    }

    private enum Outcome { ACK, NACK, RETURN, TIMEOUT, CONNECTION, CONFIRM_FAILURE }
    private record Frame(String exchange, String routingKey, Message message, String correlationId) { }
    private record Rejection(UUID consulta, UUID closure, String eventId,
                             List<Map<String, Object>> initialAudit, List<Map<String, Object>> ordersBefore) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean @Primary MutableClock billingOutboxTestClock() { return new MutableClock(); }
    }

    static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;
        private final ZoneId zone;
        MutableClock() { this(new AtomicReference<>(Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS)), ZoneOffset.UTC); }
        private MutableClock(AtomicReference<Instant> now, ZoneId zone) {
            this.now = now;
            this.zone = java.util.Objects.requireNonNull(zone);
        }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
        @Override public Instant instant() { return now.get(); }
        void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
    }
}
