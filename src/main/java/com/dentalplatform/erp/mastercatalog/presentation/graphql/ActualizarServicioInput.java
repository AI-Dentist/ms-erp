package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.command.ActualizarServicioOdontologicoCommand;

import java.math.BigDecimal;
import java.util.UUID;

public record ActualizarServicioInput(UUID especialidadId, String codigo, String nombre, String descripcion,
                                      BigDecimal precioBaseBs) {

    ActualizarServicioOdontologicoCommand toCommand(UUID id) {
        return new ActualizarServicioOdontologicoCommand(id, especialidadId, codigo, nombre, descripcion,
                precioBaseBs);
    }
}
