package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.command.ActualizarEspecialidadCommand;

import java.util.UUID;

public record ActualizarEspecialidadInput(String codigo, String nombre, String descripcion) {

    ActualizarEspecialidadCommand toCommand(UUID id) {
        return new ActualizarEspecialidadCommand(id, codigo, nombre, descripcion);
    }
}
