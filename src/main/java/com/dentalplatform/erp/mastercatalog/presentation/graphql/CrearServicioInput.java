package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.command.CrearServicioOdontologicoCommand;

import java.math.BigDecimal;
import java.util.UUID;

public record CrearServicioInput(UUID especialidadId, String codigo, String nombre, String descripcion,
                                 BigDecimal precioBaseBs) {

    CrearServicioOdontologicoCommand toCommand() {
        return new CrearServicioOdontologicoCommand(especialidadId, codigo, nombre, descripcion, precioBaseBs);
    }
}
