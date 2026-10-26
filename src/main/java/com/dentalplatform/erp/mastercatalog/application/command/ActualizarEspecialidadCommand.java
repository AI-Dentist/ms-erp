package com.dentalplatform.erp.mastercatalog.application.command;

import java.util.UUID;

public record ActualizarEspecialidadCommand(UUID id, String codigo, String nombre, String descripcion) {
}
