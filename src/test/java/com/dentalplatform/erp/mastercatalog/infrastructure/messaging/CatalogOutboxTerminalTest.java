package com.dentalplatform.erp.mastercatalog.infrastructure.messaging;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.infrastructure.outbox.CatalogOutboxDispatcher;
import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Public catalog creation, broker boundary and ADMIN audit queries; SQL is isolated due-time fixture setup only. */
@WithMockUser(username = "admin", roles = "ADMIN")
@TestPropertySource(properties = {"erp.catalog-outbox.max-attempts=3", "erp.catalog-outbox.send-timeout-ms=100",
        "erp.catalog-outbox.initial-backoff-ms=1000", "erp.catalog-outbox.max-backoff-ms=4000"})
class CatalogOutboxTerminalTest extends IntegrationTestBase {
    private static final String FAILED = "CATALOGO_EVENTO_PUBLICACION_FALLIDA";
    @Autowired CatalogOutboxDispatcher dispatcher;
    @Autowired JsonMapper json;
    @MockitoSpyBean RegistrarAuditoriaUseCase auditoria;
    private final List<Message> sent = new CopyOnWriteArrayList<>();
    private final List<CorrelationData> confirms = new CopyOnWriteArrayList<>();

    @BeforeEach void isolateFixtures() {
        jdbc.update("UPDATE catalog_event_outbox SET next_attempt_at = '2100-01-01T00:00:00Z' WHERE sent_at IS NULL");
    }

    private String create() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return graphQl.document("""
                mutation($input: CrearConsultorioInput!) { crearConsultorio(input:$input) { id estado } }
                """).variable("input", Map.of("codigo", "T-" + suffix, "nombre", "Terminal fixture " + suffix))
                .execute().path("crearConsultorio.estado").entity(String.class).isEqualTo("DISPONIBLE")
                .path("crearConsultorio.id").entity(String.class).get();
    }
    private void due(String office) {
        // Advance only this fixture's due time; assertions never inspect outbox storage.
        jdbc.update("UPDATE catalog_event_outbox SET next_attempt_at = CURRENT_TIMESTAMP WHERE payload->>'consultorioId' = ?", office);
    }
    private void broker(String failure, AtomicBoolean ack) {
        doAnswer(call -> {
            Message message = call.getArgument(2);
            CorrelationData correlation = call.getArgument(3);
            sent.add(message);
            confirms.add(correlation);
            assertThat(call.<String>getArgument(0)).isEqualTo("dental.topic.exchange");
            assertThat(call.<String>getArgument(1)).isEqualTo("catalogo.consultorio.creado");
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(correlation.getId());
            if (ack.get()) correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            else if (failure.equals("outage")) throw new AmqpException("Synthetic broker outage");
            else {
                if (failure.equals("return")) correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE",
                        "dental.topic.exchange", "catalogo.consultorio.creado"));
                if (!failure.equals("timeout")) correlation.getFuture().complete(new CorrelationData.Confirm(failure.equals("return"), null));
            }
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
    }
    private List<Map<String, Object>> failures() {
        return graphQl.document("{ listarAuditLogs(limite:500) { id accion entidadAfectada entidadId detalleJson } }")
                .execute().path("listarAuditLogs").entityList(Map.class).get().stream().map(v -> (Map<String, Object>) v)
                .filter(row -> FAILED.equals(row.get("accion"))
                        && !confirms.isEmpty() && confirms.getFirst().getId().equals(row.get("entidadId"))).toList();
    }
    private void assertTerminal(int attempts) {
        var rows = failures();
        assertThat(rows).hasSize(1);
        var detail = json.readTree(rows.getFirst().get("detalleJson").toString());
        assertThat(detail.get("estado").asString()).isEqualTo("FALLIDO");
        assertThat(detail.get("attempts").asInt()).isEqualTo(attempts);
        assertThat(detail.get("motivo").asString()).isEqualTo("PUBLICACION_NO_CONFIRMADA");
        assertThat(rows.getFirst().get("detalleJson").toString()).doesNotContain("Terminal fixture", "payload");
    }

    @ParameterizedTest @ValueSource(strings = {"outage", "nack", "return", "timeout"})
    void exhaustedEventsAreTerminalVisibleAndNeverSentAgain(String failure) {
        String office = create();
        broker(failure, new AtomicBoolean(false));
        for (int n = 1; n <= 3; n++) {
            due(office);
            dispatcher.dispatch();
            assertThat(sent).hasSize(n);
            if (n < 3) assertThat(failures()).isEmpty();
        }
        assertTerminal(3);
        List<Map<String, Object>> terminal = failures();
        confirms.getLast().getFuture().complete(new CorrelationData.Confirm(true, null)); // Late ACK is not SQL delivery.
        due(office);
        dispatcher.dispatch();
        assertThat(sent).hasSize(3);
        assertThat(failures()).isEqualTo(terminal);
        assertThat(sent).allSatisfy(message -> assertThat(message.getBody()).isEqualTo(sent.getFirst().getBody()));
    }

    @Test void successfulRetryRetainsIdentityAndDoesNotBecomeFailed() {
        String office = create();
        AtomicBoolean ack = new AtomicBoolean(false);
        broker("nack", ack);
        dispatcher.dispatch();
        assertThat(sent).hasSize(1);
        ack.set(true);
        due(office);
        dispatcher.dispatch();
        assertThat(sent).hasSize(2);
        assertThat(sent.getLast().getBody()).isEqualTo(sent.getFirst().getBody());
        assertThat(sent.getLast().getMessageProperties().getMessageId()).isEqualTo(confirms.getFirst().getId());
        due(office);
        dispatcher.dispatch();
        assertThat(sent).hasSize(2);
        assertThat(failures()).isEmpty();
    }

    @Test void terminalAuditFailureRollsBackTheDecisionAndCanBeRetried() {
        String office = create();
        broker("nack", new AtomicBoolean(false));
        for (int n = 0; n < 2; n++) { due(office); dispatcher.dispatch(); }
        AtomicBoolean unavailable = new AtomicBoolean(true);
        doAnswer(call -> {
            if (FAILED.equals(call.getArgument(0)) && unavailable.get())
                throw new TransientDataAccessResourceException("Synthetic terminal audit outage");
            return call.callRealMethod();
        }).when(auditoria).registrar(anyString(), anyString(), any(UUID.class), anyMap());
        due(office);
        assertThatThrownBy(dispatcher::dispatch).isInstanceOf(TransientDataAccessResourceException.class);
        assertThat(failures()).isEmpty();
        unavailable.set(false);
        due(office);
        dispatcher.dispatch();
        assertTerminal(3); // The failed transaction did not consume a committed attempt.
        int calls = sent.size();
        due(office);
        dispatcher.dispatch();
        assertThat(sent).hasSize(calls);
    }
}
