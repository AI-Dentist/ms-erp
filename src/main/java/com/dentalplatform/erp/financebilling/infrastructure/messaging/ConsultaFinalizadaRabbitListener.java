package com.dentalplatform.erp.financebilling.infrastructure.messaging;

import com.dentalplatform.erp.financebilling.application.usecase.ProcesarConsultaFinalizadaUseCase;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ConsultaFinalizadaRabbitListener {
    private final ProcesarConsultaFinalizadaUseCase procesar;
    private final ConsultaFinalizadaRetryPolicy retry;

    public ConsultaFinalizadaRabbitListener(ProcesarConsultaFinalizadaUseCase procesar) {
        this(procesar, new ConsultaFinalizadaRetryPolicy(3, 100, 2000,
                BillingSagaRetryConfiguration.billingSagaRetryPause()));
    }

    @Autowired
    public ConsultaFinalizadaRabbitListener(ProcesarConsultaFinalizadaUseCase procesar,
                                            ConsultaFinalizadaRetryPolicy retry) {
        this.procesar = procesar;
        this.retry = retry;
    }

    @RabbitListener(queues = "${erp.messaging.consulta-finalizada-queue}")
    public void recibir(ConsultaFinalizadaPayload payload) {
        var command = payload.toCommand();
        // Each invocation crosses the use-case proxy; rollback completes before the next attempt.
        retry.ejecutar(() -> procesar.ejecutar(command));
    }
}
