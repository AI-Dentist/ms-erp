package com.dentalplatform.erp.financebilling.infrastructure.messaging;

import com.dentalplatform.erp.financebilling.application.usecase.FacturacionSagaUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.ProcesarConsultaFinalizadaUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.RechazoFacturacionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class ConsultaFinalizadaRabbitListener {
    private static final Logger log = LoggerFactory.getLogger(ConsultaFinalizadaRabbitListener.class);
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
        AtomicInteger attempts = new AtomicInteger();
        try {
            // Each attempt crosses the saga proxy, completing rollback before retry or rejection.
            retry.ejecutar(() -> {
                attempts.incrementAndGet();
                saga.procesar(consulta, closure, () -> {
                    try {
                        return json.readValue(message.getBody(), ConsultaFinalizadaPayload.class).toCommand();
                    } catch (RuntimeException conversion) {
                        throw new RechazoFacturacionException(RechazoFacturacionException.Codigo.PAYLOAD_INVALIDO);
                    }
                });
            });
        } catch (RechazoFacturacionException rejected) {
            if (Thread.currentThread().isInterrupted()) {
                throw retained(consulta, closure, attempts.get(), rejected);
            }
            registrarRechazo(consulta, closure, rejected.codigo().name(), attempts.get());
        } catch (RuntimeException technical) {
            if (retry.agotados(technical, attempts.get())) {
                registrarRechazo(consulta, closure, "REINTENTOS_AGOTADOS", attempts.get());
            } else {
                throw retained(consulta, closure, attempts.get(), technical);
            }
        }
    }

    private void registrarRechazo(UUID consulta, UUID closure, String codigo, int attempts) {
        try {
            // One new atomic transaction reacquires the lock and rechecks billed/closure guards.
            saga.registrarRechazo(consulta, closure, codigo);
        } catch (RuntimeException persistence) {
            throw retained(consulta, closure, attempts, persistence);
        }
    }

    private ImmediateRequeueAmqpException retained(UUID consulta, UUID closure, int attempts,
                                                   RuntimeException failure) {
        log.error("event=billing.delivery.retained consultaId={} closureId={} intentos={} redeliveryDelayMs={}",
                consulta, closure, attempts, retry.redeliveryDelayMs());
        var retained = new ImmediateRequeueAmqpException("Billing delivery retained for redelivery", failure);
        try {
            retry.pauseBeforeRedelivery();
        } catch (RuntimeException cooldown) {
            // Keep the original diagnostic cause and the pause's interrupt flag, without logging either.
            retained.addSuppressed(cooldown);
        }
        return retained;
    }

    private static UUID strictUuid(String value) {
        if (value == null || value.length() != 36) { throw new IllegalArgumentException(); }
        UUID uuid = UUID.fromString(value);
        if (!uuid.toString().equalsIgnoreCase(value)) { throw new IllegalArgumentException(); }
        return uuid;
    }
}
