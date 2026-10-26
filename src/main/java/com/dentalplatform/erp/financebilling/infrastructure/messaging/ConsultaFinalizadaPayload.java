package com.dentalplatform.erp.financebilling.infrastructure.messaging;

import com.dentalplatform.erp.financebilling.application.command.ConsultaFinalizadaCommand;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ConsultaFinalizadaPayload(UUID eventId, UUID consultaId, UUID citaId, UUID pacienteId, UUID odontologoId,
                                       List<ServicioRealizado> serviciosRealizados, String observacionesCobro) {
    public record ServicioRealizado(UUID servicioId, String piezaDental, int cantidad, BigDecimal precioUnitarioBs) { }

    ConsultaFinalizadaCommand toCommand() {
        return new ConsultaFinalizadaCommand(consultaId, pacienteId, odontologoId,
                serviciosRealizados == null ? null : serviciosRealizados.stream().map(i ->
                        new ConsultaFinalizadaCommand.Item(i.servicioId(), i.piezaDental(), i.cantidad(), i.precioUnitarioBs())).toList(),
                observacionesCobro);
    }
}
