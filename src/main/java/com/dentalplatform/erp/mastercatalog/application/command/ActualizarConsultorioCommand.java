package com.dentalplatform.erp.mastercatalog.application.command;

import java.util.UUID;

/** A null estado keeps the current one. */
public record ActualizarConsultorioCommand(UUID id, String codigo, String nombre, String ubicacion, String estado) {
}
