package com.dentalplatform.erp.financebilling.infrastructure.messaging;

import com.dentalplatform.erp.financebilling.application.usecase.FacturacionSagaUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.ProcesarConsultaFinalizadaUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.RechazoFacturacionException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;

@Component
public class ConsultaFinalizadaRabbitListener {
    private final ProcesarConsultaFinalizadaUseCase procesar;
    private final ConsultaFinalizadaRetryPolicy retry;
    private final FacturacionSagaUseCase saga;
    private final JsonMapper json;

    public ConsultaFinalizadaRabbitListener(ProcesarConsultaFinalizadaUseCase procesar,
                                            ConsultaFinalizadaRetryPolicy retry,
                                            FacturacionSagaUseCase saga, JsonMapper json) {
        this.procesar = procesar;
        this.retry = retry;
        this.saga = saga;
        this.json = json;
    }

    /** Legacy callers have no AMQP closure identity and retain their business-exception contract. */
    public void recibir(ConsultaFinalizadaPayload payload) {
        var command = payload.toCommand();
        retry.ejecutar(() -> procesar.ejecutar(command));
    }

    @RabbitListener(queues = "${erp.messaging.consulta-finalizada-queue}",
            containerFactory = "billingSagaRawListenerContainerFactory")
    public void recibir(Message message) {
        UUID closure;
        UUID consulta;
        try {
            closure = strictUuid(message.getMessageProperties().getMessageId());
            Map<?, ?> body = json.readValue(message.getBody(), Map.class);
            if (body == null || !(body.get("consultaId") instanceof String consultaId)) {
                throw new IllegalArgumentException();
            }
            consulta = strictUuid(consultaId);
        } catch (RuntimeException unreadable) {
            // No body/eventId fallback or original parser message (which may contain personal data).
            throw new AmqpRejectAndDontRequeueException("Unidentifiable consultation closure");
        }
        try {
            // Each attempt crosses the saga proxy, completing rollback before retry or rejection.
            retry.ejecutar(() -> saga.procesar(consulta, closure, () -> {
                try {
                    return json.readValue(message.getBody(), ConsultaFinalizadaPayload.class).toCommand();
                } catch (RuntimeException conversion) {
                    throw new RechazoFacturacionException(RechazoFacturacionException.Codigo.PAYLOAD_INVALIDO);
                }
            }));
        } catch (RechazoFacturacionException rejected) {
            try {
                saga.registrarRechazo(consulta, closure, rejected.codigo().name());
            } catch (RuntimeException persistence) {
                throw new ImmediateRequeueAmqpException("Billing rejection could not be recorded", persistence);
            }
        } catch (RuntimeException technical) {
            // Includes exhausted transient retries until the separate exhaustion-compensation unit.
            throw new ImmediateRequeueAmqpException("Billing delivery retained for redelivery", technical);
        }
    }

    private static UUID strictUuid(String value) {
        if (value == null || value.length() != 36) { throw new IllegalArgumentException(); }
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equalsIgnoreCase(value)) { throw new IllegalArgumentException(); }
        return uuid;
    }
}
