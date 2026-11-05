package com.dentalplatform.erp.financebilling;

import com.dentalplatform.erp.financebilling.infrastructure.outbox.BillingRejectionOutboxSettings;
import com.dentalplatform.erp.financebilling.infrastructure.outbox.ConfirmedBillingRejectionSender;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.net.ConnectException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/** Transport-unit corroboration of the already-observed public outbox RED; no database or broker. */
class BillingRejectionSenderTest {
    private static final String EXCHANGE = "dental.topic.exchange";
    private static final String ROUTING = "facturacion.consulta.rechazada";

    @ParameterizedTest
    @EnumSource(Outcome.class)
    void confirmsRequireAckWithoutReturnAndKeepStablePersistentIdentity(Outcome outcome) {
        var rabbit = mock(RabbitTemplate.class);
        var frame = new AtomicReference<Message>();
        var sentCorrelation = new AtomicReference<CorrelationData>();
        doAnswer(call -> {
            Message message = call.getArgument(2);
            CorrelationData correlation = call.getArgument(3);
            frame.set(message);
            sentCorrelation.set(correlation);
            if (outcome == Outcome.CONNECTION) {
                throw new AmqpConnectException(new ConnectException("Synthetic unavailable broker"));
            }
            if (outcome == Outcome.RETURN) {
                correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE", EXCHANGE, ROUTING));
            }
            if (outcome == Outcome.CONFIRM_FAILURE) {
                correlation.getFuture().completeExceptionally(
                        new AmqpConnectException(new ConnectException("Synthetic confirm failure")));
            } else if (outcome != Outcome.TIMEOUT) {
                correlation.getFuture().complete(new CorrelationData.Confirm(outcome != Outcome.NACK, null));
            }
            return null;
        }).when(rabbit).send(eq(EXCHANGE), eq(ROUTING), any(Message.class), any(CorrelationData.class));
        var settings = new BillingRejectionOutboxSettings(3, 1, 500, 10, 20, 1000);
        var sender = new ConfirmedBillingRejectionSender(rabbit, settings);
        UUID event = UUID.randomUUID();
        String payload = """
                {"eventId":"%s","consultaId":"%s","consultaFinalizadaEventId":"%s",
                 "motivoCodigo":"PAYLOAD_INVALIDO","motivo":"Datos inválidos.",
                 "rechazadaEn":"2026-01-01T00:00:00Z"}
                """.formatted(event, UUID.randomUUID(), UUID.randomUUID());
        try {
            boolean delivered = assertDoesNotThrow(() -> sender.send(event, EXCHANGE, ROUTING, payload));
            assertThat(delivered).isEqualTo(outcome == Outcome.ACK);
            assertThat(frame.get()).isNotNull();
            assertThat(frame.get().getBody()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
            assertThat(frame.get().getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
            assertThat(frame.get().getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
            assertThat(frame.get().getMessageProperties().getMessageId()).isEqualTo(event.toString());
            assertThat(sentCorrelation.get().getId()).isEqualTo(event.toString());
            verify(rabbit).send(EXCHANGE, ROUTING, frame.get(), sentCorrelation.get());
            verifyNoMoreInteractions(rabbit);
        } finally {
            sender.close();
        }
    }

    private enum Outcome { ACK, NACK, RETURN, TIMEOUT, CONNECTION, CONFIRM_FAILURE }
}
