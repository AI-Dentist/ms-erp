package com.dentalplatform.erp.mastercatalog.application.command;

import java.math.BigDecimal;
import java.util.UUID;

public record CrearServicioOdontologicoCommand(UUID especialidadId, String codigo, String nombre, String descripcion,
                                               BigDecimal precioBaseBs) {
}
