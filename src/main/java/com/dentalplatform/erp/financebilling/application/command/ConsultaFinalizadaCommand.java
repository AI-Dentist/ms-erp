package com.dentalplatform.erp.financebilling.application.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ConsultaFinalizadaCommand(UUID consultaId, UUID pacienteId, UUID odontologoId,
                                       List<Item> serviciosRealizados, String observacionesCobro) {
    public record Item(UUID servicioId, String piezaDental, int cantidad, BigDecimal precioUnitarioBs) { }
}
