package com.dentalplatform.erp.financebilling.application.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CrearOrdenCobroCommand(UUID pacienteId, UUID odontologoId, List<Item> items,
                                    BigDecimal descuentoBs, String observaciones) {
    public record Item(UUID servicioId, String piezaDental, int cantidad) { }
}
