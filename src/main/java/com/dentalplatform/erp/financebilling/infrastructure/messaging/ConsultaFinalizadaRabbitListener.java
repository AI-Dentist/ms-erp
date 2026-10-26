package com.dentalplatform.erp.financebilling.infrastructure.messaging;

import com.dentalplatform.erp.financebilling.application.usecase.ProcesarConsultaFinalizadaUseCase;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ConsultaFinalizadaRabbitListener {
    private final ProcesarConsultaFinalizadaUseCase procesar;

    public ConsultaFinalizadaRabbitListener(ProcesarConsultaFinalizadaUseCase procesar) { this.procesar = procesar; }

    @RabbitListener(queues = "${erp.messaging.consulta-finalizada-queue}")
    public void recibir(ConsultaFinalizadaPayload payload) {
        procesar.ejecutar(payload.toCommand());
    }
}
