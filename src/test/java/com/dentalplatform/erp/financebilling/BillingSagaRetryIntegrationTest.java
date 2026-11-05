package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.application.command.ConsultaFinalizadaCommand;
import com.dentalplatform.erp.financebilling.application.usecase.ProcesarConsultaFinalizadaUseCase;
import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaPayload;
import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaRabbitListener;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.support.IntegrationTestBase;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
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
        "erp.billing-saga.retry.max-delay-ms=200"
})
class BillingSagaRetryIntegrationTest extends IntegrationTestBase {
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

        // Temporary unit boundary: rejection-outbox tests will replace this error expectation
        // with durable compensation; this test does not establish compensation behavior.
        assertThatThrownBy(() -> deliver(message))
                .hasRootCauseInstanceOf(DataAccessResourceFailureException.class)
                .hasRootCauseMessage("Synthetic persistent database outage");

        verify(processor, times(4)).ejecutar(any(ConsultaFinalizadaCommand.class));
        assertPauses(100, 200, 200);
        assertNoOrders(fixture);
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

        verify(processor, times(2)).ejecutar(any(ConsultaFinalizadaCommand.class));
        assertThat(assertSinglePendingOrder(fixture)).isEqualTo(orderId);
        verifyNoInteractions(pause);
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
