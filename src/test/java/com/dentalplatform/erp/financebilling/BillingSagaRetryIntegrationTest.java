package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.command.ConsultaFinalizadaCommand;
import com.dentalplatform.erp.financebilling.application.usecase.FacturacionSagaUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.ProcesarConsultaFinalizadaUseCase;
import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaPayload;
import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaRabbitListener;
import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaRetryPolicy;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.support.IntegrationTestBase;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

@WithMockUser(username = "admin", roles = "ADMIN")
@TestPropertySource(properties = {
        "erp.billing-saga.retry.max-attempts=4",
        "erp.billing-saga.retry.initial-delay-ms=100",
        "erp.billing-saga.retry.max-delay-ms=200",
        "erp.billing-saga.retry.redelivery-delay-ms=1000"
})
class BillingSagaRetryIntegrationTest extends IntegrationTestBase {
    private static final String PRIVATE_DIAGNOSTIC = "private-diagnostic-not-for-logs";
    private static final String REJECTED = "FACTURACION_CONSULTA_RECHAZADA";
    private static final String AUDIT = """
            query { listarAuditLogs(limite: 500) { accion entidadAfectada entidadId detalleJson } }""";
    private static final String ORDERS = """
            query($patient: UUID!) {
              listarOrdenesCobro(pacienteId: $patient) {
                id consultaExternaId estado modalidadPago montoSubtotalBs montoTotalBs saldoPendienteBs
                items { servicio { id } cantidad precioUnitarioBs subtotalBs }
              }
            }""";

    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired JsonMapper jsonMapper;
    @Autowired ConsultaFinalizadaRabbitListener listener;
    @Value("${erp.messaging.consulta-finalizada-queue}") String consultationQueue;
    @MockitoSpyBean ProcesarConsultaFinalizadaUseCase processor;
    @MockitoSpyBean FacturacionSagaUseCase saga;
    @MockitoBean(name = "billingSagaRetryPause") LongConsumer pause;

    @Test
    void transientDatabaseFailureIsRetriedBeforeDeliveryCompletes() {
        Fixture fixture = createFixture();
        Message message = message(fixture);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            // Both the failing attempt and the retry must start without a financial order.
            assertNoOrders(fixture);
            if (attempts.incrementAndGet() == 1) {
                throw new DataAccessResourceFailureException("Synthetic temporary database outage");
            }
            return invocation.callRealMethod();
        }).when(processor).ejecutar(any(ConsultaFinalizadaCommand.class));

        assertDoesNotThrow(() -> deliver(message));

        verify(processor, times(2)).ejecutar(any(ConsultaFinalizadaCommand.class));
        assertPauses(100);
        assertSinglePendingOrder(fixture);
    }

    @Test
    void fourthAttemptSucceedsWithExponentialBackoffCappedAtMaximumDelay() {
        Fixture fixture = createFixture();
        Message message = message(fixture);
        AtomicInteger attempts = new AtomicInteger();
        doAnswer(invocation -> {
            assertNoOrders(fixture);
            if (attempts.incrementAndGet() < 4) {
                throw new DataAccessResourceFailureException("Synthetic temporary database outage");
            }
            return invocation.callRealMethod();
        }).when(processor).ejecutar(any(ConsultaFinalizadaCommand.class));

        assertDoesNotThrow(() -> deliver(message));

        verify(processor, times(4)).ejecutar(any(ConsultaFinalizadaCommand.class));
        assertPauses(100, 200, 200);
        assertSinglePendingOrder(fixture);
    }

    @Test
    void exhaustedTransientFailureStopsAfterFourAttemptsWithoutCreatingAnOrder() {
        Fixture fixture = createFixture();
        Message message = message(fixture);
        doAnswer(invocation -> {
            assertNoOrders(fixture);
            throw new DataAccessResourceFailureException("Synthetic persistent database outage");
        }).when(processor).ejecutar(any(ConsultaFinalizadaCommand.class));

        assertDoesNotThrow(() -> deliver(message));

        verify(processor, times(4)).ejecutar(any(ConsultaFinalizadaCommand.class));
        assertPauses(100, 200, 200);
        assertNoOrders(fixture);
        assertExhaustionRejection(fixture, message);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void failedExhaustionRejectionPersistenceRequeuesUntilSameDeliveryCanBeRecorded(CapturedOutput output) {
        Fixture fixture = createFixture();
        Message message = message(fixture);
        UUID closure = UUID.fromString(message.getMessageProperties().getMessageId());
        doAnswer(invocation -> {
            throw new DataAccessResourceFailureException("Synthetic persistent database outage");
        }).when(processor).ejecutar(any(ConsultaFinalizadaCommand.class));
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (failed.compareAndSet(false, true)) {
                throw new DataAccessResourceFailureException(PRIVATE_DIAGNOSTIC);
            }
            return invocation.callRealMethod();
        }).when(saga).registrarRechazo(eq(fixture.consultation()), eq(closure), eq("REINTENTOS_AGOTADOS"));

        assertThatThrownBy(() -> deliver(message)).satisfies(error ->
                assertThat(causeChain(error)).anyMatch(ImmediateRequeueAmqpException.class::isInstance));
        assertNoOrders(fixture);
        assertThat(rejections(fixture.consultation())).isEmpty();
        verify(saga).registrarRechazo(fixture.consultation(), closure, "REINTENTOS_AGOTADOS");
        verify(processor, times(4)).ejecutar(any(ConsultaFinalizadaCommand.class));
        assertPauses(100, 200, 200, 1000);
        assertRetainedLog(output, fixture, message, 4);

        assertDoesNotThrow(() -> deliver(message));

        verify(saga, times(2)).registrarRechazo(fixture.consultation(), closure, "REINTENTOS_AGOTADOS");
        verify(processor, times(8)).ejecutar(any(ConsultaFinalizadaCommand.class));
        assertPauses(100, 200, 200, 1000, 100, 200, 200);
        assertNoOrders(fixture);
        assertExhaustionRejection(fixture, message);
        assertRetainedLog(output, fixture, message, 4);
    }

    @Test
    void correctedNewClosureBillsSameConsultationWithoutRevivingExhaustedClosure() {
        Fixture fixture = createFixture();
        Message oldClosure = message(fixture);
        AtomicBoolean unavailable = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (unavailable.get()) {
                throw new DataAccessResourceFailureException("Synthetic persistent database outage");
            }
            return invocation.callRealMethod();
        }).when(processor).ejecutar(any(ConsultaFinalizadaCommand.class));

        assertDoesNotThrow(() -> deliver(oldClosure));
        assertNoOrders(fixture);
        assertExhaustionRejection(fixture, oldClosure);
        var rejectionBefore = rejections(fixture.consultation());
        unavailable.set(false);
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(UUID.randomUUID().toString());
        assertThat(properties.getMessageId()).isNotEqualTo(oldClosure.getMessageProperties().getMessageId());
        // Only the AMQP closure identity changes: same consulta, patient, service and body eventId.
        Message correctedClosure = new Message(oldClosure.getBody(), properties);

        assertDoesNotThrow(() -> deliver(correctedClosure));
        String orderId = assertSinglePendingOrder(fixture);
        assertThat(rejections(fixture.consultation(), properties.getMessageId())).isEmpty();
        assertDoesNotThrow(() -> deliver(oldClosure));

        assertThat(assertSinglePendingOrder(fixture)).isEqualTo(orderId);
        assertThat(rejections(fixture.consultation())).isEqualTo(rejectionBefore);
        verify(processor, times(5)).ejecutar(any(ConsultaFinalizadaCommand.class));
        assertPauses(100, 200, 200);
    }

    @Test
    @SuppressWarnings("unchecked")
    void alreadyBilledConsultationSkipsInvalidDtoAndHostileTransportMetadata() {
        Fixture fixture = createFixture();
        Message valid = message(fixture);
        assertDoesNotThrow(() -> deliver(valid));
        String orderId = assertSinglePendingOrder(fixture);
        Map<String, Object> body = body(valid);
        ((List<Map<String, Object>>) body.get("serviciosRealizados")).getFirst().put("cantidad", "dos");
        MessageProperties properties = new MessageProperties();
        properties.setMessageId(UUID.randomUUID().toString());
        properties.setContentType("application/x-java-serialized-object");
        properties.setHeader("__TypeId__", "no.such.UntrustedType");
        Message hostile = new Message(jsonMapper.writeValueAsBytes(body), properties);

        assertDoesNotThrow(() -> deliver(hostile));

        assertThat(assertSinglePendingOrder(fixture)).isEqualTo(orderId);
        assertThat(rejections(fixture.consultation())).isEmpty();
        verify(processor).ejecutar(any(ConsultaFinalizadaCommand.class));
        verifyNoInteractions(pause);
    }

    @Test
    void newClosureBBillingWinsAgainstLateRejectionOfOldClosureA() throws Exception {
        Fixture fixture = createFixture();
        UUID oldClosureA = UUID.randomUUID();
        UUID newClosureB = UUID.randomUUID();
        assertThat(newClosureB).isNotEqualTo(oldClosureA);
        Message message = message(fixture);
        message.getMessageProperties().setMessageId(newClosureB.toString());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch rejectionStarted = new CountDownLatch(1);
        doAnswer(invocation -> {
            // The saga owns the consultation lock before invoking this processor proxy.
            entered.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(processor).ejecutar(any(ConsultaFinalizadaCommand.class));
        FutureTask<Void> billing = new FutureTask<>(() -> { deliver(message); return null; });
        FutureTask<Void> rejection = new FutureTask<>(() -> {
            rejectionStarted.countDown();
            saga.registrarRechazo(fixture.consultation(), oldClosureA, "PAYLOAD_INVALIDO");
            return null;
        });
        Thread.ofVirtual().start(billing);
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            Thread.ofVirtual().start(rejection);
            assertThat(rejectionStarted.await(10, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            billing.get(10, TimeUnit.SECONDS);
            rejection.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }

        // GraphQL stays on the authenticated test thread; broker actors may audit as SYSTEM.
        assertSinglePendingOrder(fixture);
        assertThat(rejections(fixture.consultation())).isEmpty();
        verify(saga).registrarRechazo(fixture.consultation(), oldClosureA, "PAYLOAD_INVALIDO");
        verify(processor).ejecutar(any(ConsultaFinalizadaCommand.class));
        verifyNoInteractions(pause);
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void unknownNonTransientTechnicalFailureRequeuesWithCooldownWithoutRetryOrCompensation(CapturedOutput output) {
        Fixture fixture = createFixture();
        Message message = message(fixture);
        doAnswer(invocation -> {
            throw new IllegalStateException(PRIVATE_DIAGNOSTIC);
        }).when(processor).ejecutar(any(ConsultaFinalizadaCommand.class));

        assertThatThrownBy(() -> deliver(message)).satisfies(error -> {
            assertThat(causeChain(error)).anyMatch(ImmediateRequeueAmqpException.class::isInstance);
            assertThat(causeChain(error)).anyMatch(cause -> cause instanceof IllegalStateException
                    && PRIVATE_DIAGNOSTIC.equals(cause.getMessage()));
        });

        verify(processor).ejecutar(any(ConsultaFinalizadaCommand.class));
        assertPauses(1000);
        assertNoOrders(fixture);
        assertThat(rejections(fixture.consultation())).isEmpty();
        assertRetainedLog(output, fixture, message, 1);
    }

    @Test
    void legacyTypedEntryRejectsUnknownPatientWithoutRetry() {
        Fixture fixture = createFixture();
        UUID unknownPatient = UUID.randomUUID();
        var payload = payload(fixture, unknownPatient);
        assertNoOrders(fixture);

        // Legacy typed delivery has no AMQP messageId and retains its exception contract.
        assertThatThrownBy(() -> listener.recibir(payload))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El paciente no existe: " + unknownPatient);

        verify(processor).ejecutar(any(ConsultaFinalizadaCommand.class));
        verifyNoInteractions(pause);
        assertNoOrders(fixture);
        graphQl.document(ORDERS).variable("patient", unknownPatient.toString()).execute()
                .path("listarOrdenesCobro").entityList(Object.class).hasSize(0);
    }

    @Test
    void validDeliveryUsesFinancialEventPriceInsteadOfCatalogPrice() {
        Fixture fixture = createFixture();
        assertNoOrders(fixture);

        assertDoesNotThrow(() -> deliver(message(fixture)));

        verify(processor).ejecutar(any(ConsultaFinalizadaCommand.class));
        verifyNoInteractions(pause);
        assertSinglePendingOrder(fixture);
    }

    @Test
    void duplicateRawDeliveryKeepsTheSameSingleOrder() {
        Fixture fixture = createFixture();
        Message message = message(fixture);
        assertDoesNotThrow(() -> deliver(message));
        String orderId = assertSinglePendingOrder(fixture);

        assertDoesNotThrow(() -> deliver(message));

        // The saga guard now returns before conversion/processor invocation for an already billed consultation.
        verify(processor).ejecutar(any(ConsultaFinalizadaCommand.class));
        assertThat(assertSinglePendingOrder(fixture)).isEqualTo(orderId);
        verifyNoInteractions(pause);
    }

    private void assertRetainedLog(CapturedOutput output, Fixture fixture, Message message, int attempts) {
        var retained = output.getOut().lines()
                .filter(line -> line.contains("event=billing.delivery.retained")).toList();
        assertThat(retained).hasSize(1);
        assertThat(retained.getFirst()).contains("ERROR", "consultaId=" + fixture.consultation(),
                "closureId=" + message.getMessageProperties().getMessageId(),
                "intentos=" + attempts, "redeliveryDelayMs=1000");
        assertThat(output.getAll()).doesNotContain(PRIVATE_DIAGNOSTIC,
                "Synthetic persistent database outage", "Prueba sintetica de saga", "\"serviciosRealizados\"");
    }

    private void assertExhaustionRejection(Fixture fixture, Message message) {
        String closure = message.getMessageProperties().getMessageId();
        String bodyEventId = body(message).get("eventId").toString();
        assertThat(closure).isNotEqualTo(bodyEventId);
        var entries = rejections(fixture.consultation());
        assertThat(entries).hasSize(1);
        var entry = entries.getFirst();
        assertThat(entry.get("entidadAfectada")).isEqualTo("rechazos_facturacion");
        String compensationId = (String) entry.get("entidadId");
        assertThat(UUID.fromString(compensationId)).isNotNull();
        assertThat(compensationId).isNotEqualTo(closure).isNotEqualTo(bodyEventId)
                .isNotEqualTo(fixture.consultation().toString());
        assertThat(detail(entry)).containsEntry("consultaId", fixture.consultation().toString())
                .containsEntry("consultaFinalizadaEventId", closure)
                .containsEntry("motivoCodigo", "REINTENTOS_AGOTADOS");
        assertThat(rejections(fixture.consultation(), closure)).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rejections(UUID consultation) {
        return graphQl.document(AUDIT).execute().path("listarAuditLogs").entityList(Map.class).get().stream()
                .map(row -> (Map<String, Object>) row)
                .filter(row -> REJECTED.equals(row.get("accion")))
                .filter(row -> consultation.toString().equals(detail(row).get("consultaId"))).toList();
    }

    private List<Map<String, Object>> rejections(UUID consultation, String closure) {
        return rejections(consultation).stream()
                .filter(row -> closure.equals(detail(row).get("consultaFinalizadaEventId"))).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(Map<String, Object> entry) {
        return jsonMapper.readValue((String) entry.get("detalleJson"), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(Message message) {
        return jsonMapper.readValue(message.getBody(), Map.class);
    }

    private static List<Throwable> causeChain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cause = error; cause != null && !chain.contains(cause); cause = cause.getCause()) {
            chain.add(cause);
        }
        return chain;
    }

    private Fixture createFixture() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String patient = graphQl.document("""
                mutation($input: CrearPacienteInput!) { crearPaciente(input: $input) { id } }
                """).variable("input", Map.of("ciNit", "SAGA-" + suffix, "nombres", "Paciente Sintetico",
                "apellidos", "Prueba", "telefonoWhatsapp", "+59170000000"))
                .execute().path("crearPaciente.id").entity(String.class).get();
        String dentist = graphQl.document("""
                mutation($input: CrearOdontologoInput!) { crearOdontologo(input: $input) { id } }
                """).variable("input", Map.of("ci", "CI-" + suffix, "matriculaProfesional", "MAT-" + suffix,
                "nombres", "Odontologo Sintetico", "apellidos", "Prueba", "telefono", "+59170000000",
                "porcentajeComision", 35, "especialidadesIds", List.of()))
                .execute().path("crearOdontologo.id").entity(String.class).get();
        String service = graphQl.document("""
                mutation($input: CrearServicioInput!) {
                  crearServicioOdontologico(input: $input) { id precioBaseBs }
                }
                """).variable("input", Map.of("codigo", "SAGA-" + suffix, "nombre", "Servicio sintetico",
                "precioBaseBs", 100))
                .execute().path("crearServicioOdontologico.precioBaseBs").entity(Double.class).isEqualTo(100.0)
                .path("crearServicioOdontologico.id").entity(String.class).get();
        return new Fixture(patient, dentist, service, UUID.randomUUID());
    }

    private ConsultaFinalizadaPayload payload(Fixture fixture, UUID patient) {
        return new ConsultaFinalizadaPayload(UUID.randomUUID(), fixture.consultation(), UUID.randomUUID(),
                patient, UUID.fromString(fixture.dentist()),
                List.of(new ConsultaFinalizadaPayload.ServicioRealizado(UUID.fromString(fixture.service()),
                        "36", 2, new BigDecimal("250"))), "Prueba sintetica de saga");
    }

    private Message message(Fixture fixture) {
        var payload = payload(fixture, UUID.fromString(fixture.patient()));
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(UUID.randomUUID().toString());
        assertThat(properties.getMessageId()).isNotEqualTo(payload.eventId().toString());
        return new Message(jsonMapper.writeValueAsBytes(payload), properties);
    }

    private void deliver(Message message) throws Exception {
        // Real registered annotation adapter: exercises invocation-owned retry, not container advice or wire ACK/DLQ.
        var containers = registry.getListenerContainers().stream()
                .filter(AbstractMessageListenerContainer.class::isInstance)
                .map(AbstractMessageListenerContainer.class::cast)
                .filter(container -> Arrays.asList(container.getQueueNames()).contains(consultationQueue))
                .toList();
        assertThat(containers).hasSize(1);
        Object adapter = containers.getFirst().getMessageListener();
        assertThat(adapter).isInstanceOf(ChannelAwareMessageListener.class);
        ((ChannelAwareMessageListener) adapter).onMessage(message, mock(Channel.class));
    }

    private void assertPauses(long... delays) {
        var ordered = inOrder(pause);
        for (int start = 0; start < delays.length;) {
            int end = start + 1;
            while (end < delays.length && delays[end] == delays[start]) {
                end++;
            }
            ordered.verify(pause, times(end - start)).accept(delays[start]);
            start = end;
        }
        verifyNoMoreInteractions(pause);
    }

    private void assertNoOrders(Fixture fixture) {
        graphQl.document(ORDERS).variable("patient", fixture.patient()).execute()
                .path("listarOrdenesCobro").entityList(Object.class).hasSize(0);
    }

    private String assertSinglePendingOrder(Fixture fixture) {
        return graphQl.document(ORDERS).variable("patient", fixture.patient()).execute()
                .path("listarOrdenesCobro").entityList(Object.class).hasSize(1)
                .path("listarOrdenesCobro[0].consultaExternaId").entity(String.class)
                .isEqualTo(fixture.consultation().toString())
                .path("listarOrdenesCobro[0].estado").entity(String.class).isEqualTo("PENDIENTE")
                .path("listarOrdenesCobro[0].modalidadPago").entity(String.class).isEqualTo("CONTADO")
                .path("listarOrdenesCobro[0].montoSubtotalBs").entity(Double.class).isEqualTo(500.0)
                .path("listarOrdenesCobro[0].montoTotalBs").entity(Double.class).isEqualTo(500.0)
                .path("listarOrdenesCobro[0].saldoPendienteBs").entity(Double.class).isEqualTo(500.0)
                .path("listarOrdenesCobro[0].items").entityList(Object.class).hasSize(1)
                .path("listarOrdenesCobro[0].items[0].servicio.id").entity(String.class).isEqualTo(fixture.service())
                .path("listarOrdenesCobro[0].items[0].cantidad").entity(Integer.class).isEqualTo(2)
                .path("listarOrdenesCobro[0].items[0].precioUnitarioBs").entity(Double.class).isEqualTo(250.0)
                .path("listarOrdenesCobro[0].items[0].subtotalBs").entity(Double.class).isEqualTo(500.0)
                .path("listarOrdenesCobro[0].id").entity(String.class).get();
    }

    private record Fixture(String patient, String dentist, String service, UUID consultation) { }
}

// Isolated policy validation: no IntegrationTestBase, application bootstrap or database.
class BillingSagaRetryPolicyBoundsTest {
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(ConsultaFinalizadaRetryPolicy.class)
            .withBean("billingSagaRetryPause", LongConsumer.class, () -> ignored -> { });

    @ParameterizedTest
    @ValueSource(ints = {1, 11})
    void businessAttemptLimitRejectsValuesOutsideTwoThroughTen(int attempts) {
        context.withPropertyValues("erp.billing-saga.retry.max-attempts=" + attempts)
                .run(application -> assertThat(application).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 30001})
    void redeliveryCooldownRejectsValuesOutsideOneHundredThroughThirtyThousand(int delay) {
        context.withPropertyValues("erp.billing-saga.retry.redelivery-delay-ms=" + delay)
                .run(application -> assertThat(application).hasFailed());
    }
}
