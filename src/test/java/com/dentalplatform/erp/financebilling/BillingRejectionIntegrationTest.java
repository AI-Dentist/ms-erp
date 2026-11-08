package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaPayload;
import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaRabbitListener;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.support.IntegrationTestBase;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.api.ChannelAwareMessageListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

@WithMockUser(username = "admin", roles = "ADMIN")
class BillingRejectionIntegrationTest extends IntegrationTestBase {
    private static final String REJECTED = "FACTURACION_CONSULTA_RECHAZADA";
    private static final String ORDERS = """
            query($patient: UUID!) {
              listarOrdenesCobro(pacienteId: $patient) { id consultaExternaId estado montoTotalBs }
            }""";
    private static final String AUDIT = """
            query { listarAuditLogs(limite: 500) { accion entidadAfectada entidadId detalleJson } }""";

    @Autowired RabbitListenerEndpointRegistry registry;
    @Autowired JsonMapper jsonMapper;
    @Autowired ConsultaFinalizadaRabbitListener listener;
    @Value("${erp.messaging.consulta-finalizada-queue}") String consultationQueue;
    @MockitoSpyBean RegistrarAuditoriaUseCase auditoria;
    @MockitoBean(name = "billingSagaRetryPause") LongConsumer pause;

    // --- Rule 1: one definitive business code per test (S9 item 2, S1) ---

    @Test
    void unknownPatientIsRejectedAsPacienteInexistente() throws Exception {
        Fixture f = createFixture(false);
        Map<String, Object> body = body(f);
        body.put("pacienteId", UUID.randomUUID());
        assertRejectedOnce(f, body, "PACIENTE_INEXISTENTE");
    }

    @Test
    void unknownDentistIsRejectedAsOdontologoInexistente() throws Exception {
        Fixture f = createFixture(false);
        Map<String, Object> body = body(f);
        body.put("odontologoId", UUID.randomUUID());
        assertRejectedOnce(f, body, "ODONTOLOGO_INEXISTENTE");
    }

    @Test
    void unknownServiceIsRejectedAsServicioInexistente() throws Exception {
        Fixture f = createFixture(false);
        Map<String, Object> body = body(f);
        body.put("serviciosRealizados", List.of(item(UUID.randomUUID().toString(), 1, "100")));
        assertRejectedOnce(f, body, "SERVICIO_INEXISTENTE");
    }

    @Test
    void deactivatedServiceIsRejectedAsServicioInactivo() throws Exception {
        Fixture f = createFixture(true);
        assertRejectedOnce(f, body(f), "SERVICIO_INACTIVO");
    }

    @Test
    void negativePriceThatRoundsAwayFromZeroIsRejectedAsMontoInvalido() throws Exception {
        Fixture f = createFixture(false);
        Map<String, Object> body = body(f);
        body.put("serviciosRealizados", List.of(item(f.service(), 1, "-0.005")));
        assertRejectedOnce(f, body, "MONTO_INVALIDO");
    }

    @Test
    void zeroQuantityIsRejectedAsPayloadInvalido() throws Exception {
        Fixture f = createFixture(false);
        Map<String, Object> body = body(f);
        body.put("serviciosRealizados", List.of(item(f.service(), 0, "100")));
        assertRejectedOnce(f, body, "PAYLOAD_INVALIDO");
    }

    // --- Rule 2: readable but non-convertible body ---

    @Test
    void nonConvertibleBodyWithValidCorrelationIsRejectedAsPayloadInvalido() throws Exception {
        Fixture f = createFixture(false);
        Map<String, Object> body = body(f);
        body.put("serviciosRealizados", List.of(item(f.service(), "dos", "100")));
        assertRejectedOnce(f, body, "PAYLOAD_INVALIDO");
    }

    // --- Rule 3: poison messages are the only DLQ path ---

    @Test
    void bodyThatIsNotJsonIsPoison() {
        Message message = message("this is not json".getBytes(StandardCharsets.UTF_8), UUID.randomUUID().toString());
        assertPoison(message, UUID.randomUUID());
    }

    @Test
    void bodyWithoutConsultaIdIsPoison() throws Exception {
        Fixture f = createFixture(false);
        Map<String, Object> body = body(f);
        body.remove("consultaId");
        assertPoison(message(body, UUID.randomUUID().toString()), f.consultation());
    }

    @Test
    void missingMessageIdIsPoison() throws Exception {
        Fixture f = createFixture(false);
        assertPoison(message(body(f), null), f.consultation());
    }

    @Test
    void nonUuidMessageIdIsPoison() throws Exception {
        Fixture f = createFixture(false);
        assertPoison(message(body(f), "not-a-uuid"), f.consultation());
    }

    // --- Rule 4: duplicate rejected closure ---

    @Test
    void sameRejectedClosureDeliveredTwiceIsRecordedOnce() throws Exception {
        Fixture f = createFixture(false);
        Map<String, Object> body = body(f);
        body.put("pacienteId", UUID.randomUUID());
        Message message = message(body, UUID.randomUUID().toString());

        assertDoesNotThrow(() -> deliver(message));
        assertDoesNotThrow(() -> deliver(message));

        assertThat(rejections(f.consultation())).hasSize(1);
        assertNoOrders(body.get("pacienteId").toString());
    }

    // --- Rule 5: a rejected closure stays rejected ---

    @Test
    void rejectedClosureIsNeverBilledByLaterDeliveryOfTheSameMessage() throws Exception {
        Fixture f = createFixture(false);
        String messageId = UUID.randomUUID().toString();
        Map<String, Object> bad = body(f);
        bad.put("odontologoId", UUID.randomUUID());
        assertDoesNotThrow(() -> deliver(message(bad, messageId)));
        assertThat(rejections(f.consultation())).hasSize(1);

        assertDoesNotThrow(() -> deliver(message(body(f), messageId)));

        assertNoOrders(f.patient());
        assertThat(rejections(f.consultation())).hasSize(1);
    }

    // --- Rule 6: persistence failure of the rejection never ACKs ---

    @Test
    void failedRejectionPersistenceRequeuesAndLaterRedeliverySucceeds() throws Exception {
        Fixture f = createFixture(false);
        Map<String, Object> body = body(f);
        body.put("pacienteId", UUID.randomUUID());
        Message message = message(body, UUID.randomUUID().toString());
        AtomicBoolean failed = new AtomicBoolean();
        doAnswer(invocation -> {
            if (failed.compareAndSet(false, true)) {
                throw new DataAccessResourceFailureException("Synthetic audit outage");
            }
            return invocation.callRealMethod();
        }).when(auditoria).registrar(eq(REJECTED), any(), any(), any());

        assertThatThrownBy(() -> deliver(message)).satisfies(e ->
                assertThat(causeChain(e)).anyMatch(ImmediateRequeueAmqpException.class::isInstance));
        assertThat(rejections(f.consultation())).isEmpty();
        assertNoOrders(f.patient());

        assertDoesNotThrow(() -> deliver(message));
        assertThat(rejections(f.consultation())).hasSize(1);
        assertNoOrders(f.patient());
    }

    // --- Rule 7: previous behavior ---

    @Test
    void validDeliveryStillCreatesOnePendingOrderAndNoRejection() throws Exception {
        Fixture f = createFixture(false);

        assertDoesNotThrow(() -> deliver(message(body(f), UUID.randomUUID().toString())));

        graphQl.document(ORDERS).variable("patient", f.patient()).execute()
                .path("listarOrdenesCobro").entityList(Object.class).hasSize(1)
                .path("listarOrdenesCobro[0].estado").entity(String.class).isEqualTo("PENDIENTE")
                .path("listarOrdenesCobro[0].montoTotalBs").entity(Double.class).isEqualTo(500.0);
        assertThat(rejections(f.consultation())).isEmpty();
    }

    @Test
    void typedEntryStillThrowsBusinessRuleExceptionWithoutRejectionRecord() throws Exception {
        Fixture f = createFixture(false);
        UUID unknownPatient = UUID.randomUUID();
        var payload = new ConsultaFinalizadaPayload(UUID.randomUUID(), f.consultation(), UUID.randomUUID(),
                unknownPatient, UUID.fromString(f.dentist()),
                List.of(new ConsultaFinalizadaPayload.ServicioRealizado(UUID.fromString(f.service()),
                        "36", 2, new BigDecimal("250"))), "Prueba sintetica de rechazo");

        assertThatThrownBy(() -> listener.recibir(payload))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El paciente no existe: " + unknownPatient);

        assertThat(rejections(f.consultation())).isEmpty();
    }

    // --- Helpers ---

    private void assertRejectedOnce(Fixture f, Map<String, Object> body, String code) throws Exception {
        String messageId = UUID.randomUUID().toString();
        assertThat(messageId).isNotEqualTo(body.get("eventId").toString());

        assertDoesNotThrow(() -> deliver(message(body, messageId)));

        List<Map<String, Object>> rejections = rejections(f.consultation());
        assertThat(rejections).hasSize(1);
        Map<String, Object> entry = rejections.getFirst();
        assertThat(entry.get("entidadAfectada")).isEqualTo("rechazos_facturacion");
        assertThat(entry.get("entidadId")).isNotNull()
                .isNotEqualTo(f.consultation().toString()).isNotEqualTo(messageId);
        Map<String, Object> detail = detail(entry);
        assertThat(detail.get("consultaId")).isEqualTo(f.consultation().toString());
        assertThat(detail.get("consultaFinalizadaEventId")).isEqualTo(messageId);
        assertThat(detail.get("motivoCodigo")).isEqualTo(code);
        assertNoOrders(f.patient());
        assertNoOrders(String.valueOf(body.get("pacienteId")));
    }

    private void assertPoison(Message message, UUID consultation) {
        var ordersBefore = orderIds();
        var rejectionsBefore = rejectionIds();
        assertThatThrownBy(() -> deliver(message)).satisfies(e ->
                assertThat(causeChain(e)).anyMatch(AmqpRejectAndDontRequeueException.class::isInstance));
        assertThat(rejections(consultation)).isEmpty();
        assertThat(orderIds()).containsExactlyInAnyOrderElementsOf(ordersBefore);
        assertThat(rejectionIds()).containsExactlyInAnyOrderElementsOf(rejectionsBefore);
    }

    private List<String> orderIds() {
        return graphQl.document("{ listarOrdenesCobro { id } }").execute()
                .path("listarOrdenesCobro[*].id").entityList(String.class).get();
    }

    private List<String> rejectionIds() {
        return graphQl.document(AUDIT).execute().path("listarAuditLogs").entityList(Map.class).get().stream()
                .filter(row -> REJECTED.equals(row.get("accion")))
                .map(row -> row.get("entidadId").toString()).toList();
    }

    private static List<Throwable> causeChain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable t = error; t != null && !chain.contains(t); t = t.getCause()) {
            chain.add(t);
        }
        return chain;
    }

    private List<Map<String, Object>> rejections(UUID consultation) {
        List<Map<String, Object>> logs = graphQl.document(AUDIT).execute()
                .path("listarAuditLogs").entityList(Map.class).get().stream()
                .map(m -> {
                    @SuppressWarnings("unchecked") Map<String, Object> typed = (Map<String, Object>) m;
                    return typed;
                }).toList();
        return logs.stream()
                .filter(m -> REJECTED.equals(m.get("accion")))
                .filter(m -> consultation.toString().equals(detail(m).get("consultaId")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> detail(Map<String, Object> entry) {
        return jsonMapper.readValue((String) entry.get("detalleJson"), Map.class);
    }

    private void assertNoOrders(String patient) {
        graphQl.document(ORDERS).variable("patient", patient).execute()
                .path("listarOrdenesCobro").entityList(Object.class).hasSize(0);
    }

    private Fixture createFixture(boolean deactivateService) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String patient = graphQl.document("""
                mutation($input: CrearPacienteInput!) { crearPaciente(input: $input) { id } }
                """).variable("input", Map.of("ciNit", "REJ-" + suffix, "nombres", "Paciente Sintetico",
                "apellidos", "Prueba", "telefonoWhatsapp", "+59170000000"))
                .execute().path("crearPaciente.id").entity(String.class).get();
        String dentist = graphQl.document("""
                mutation($input: CrearOdontologoInput!) { crearOdontologo(input: $input) { id } }
                """).variable("input", Map.of("ci", "CI-" + suffix, "matriculaProfesional", "MAT-" + suffix,
                "nombres", "Odontologo Sintetico", "apellidos", "Prueba", "telefono", "+59170000000",
                "porcentajeComision", 35, "especialidadesIds", List.of()))
                .execute().path("crearOdontologo.id").entity(String.class).get();
        String service = graphQl.document("""
                mutation($input: CrearServicioInput!) { crearServicioOdontologico(input: $input) { id } }
                """).variable("input", Map.of("codigo", "REJ-" + suffix, "nombre", "Servicio sintetico",
                "precioBaseBs", 100))
                .execute().path("crearServicioOdontologico.id").entity(String.class).get();
        if (deactivateService) {
            graphQl.document("mutation($id: UUID!) { eliminarServicioOdontologico(id: $id) }")
                    .variable("id", service).execute().path("eliminarServicioOdontologico")
                    .entity(Boolean.class).isEqualTo(true);
        }
        return new Fixture(patient, dentist, service, UUID.randomUUID());
    }

    private Map<String, Object> body(Fixture f) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("eventId", UUID.randomUUID());
        body.put("consultaId", f.consultation());
        body.put("citaId", UUID.randomUUID());
        body.put("pacienteId", f.patient());
        body.put("odontologoId", f.dentist());
        body.put("serviciosRealizados", List.of(item(f.service(), 2, "250")));
        body.put("observacionesCobro", "Prueba sintetica de rechazo");
        return body;
    }

    private Map<String, Object> item(String service, Object quantity, String price) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("servicioId", service);
        item.put("piezaDental", "36");
        item.put("cantidad", quantity);
        item.put("precioUnitarioBs", new BigDecimal(price));
        return item;
    }

    private Message message(Map<String, Object> body, String messageId) throws Exception {
        return message(jsonMapper.writeValueAsBytes(body), messageId);
    }

    private Message message(byte[] bytes, String messageId) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        if (messageId != null) {
            properties.setMessageId(messageId);
        }
        return new Message(bytes, properties);
    }

    private void deliver(Message message) throws Exception {
        // Real registered annotation adapter, as in BillingSagaRetryIntegrationTest.
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

    private record Fixture(String patient, String dentist, String service, UUID consultation) { }
}
