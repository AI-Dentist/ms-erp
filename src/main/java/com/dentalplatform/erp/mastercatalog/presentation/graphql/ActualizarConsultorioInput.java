package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.command.ActualizarConsultorioCommand;

import java.util.UUID;

public record ActualizarConsultorioInput(String codigo, String nombre, String ubicacion, String estado) {

    ActualizarConsultorioCommand toCommand(UUID id) {
        return new ActualizarConsultorioCommand(id, codigo, nombre, ubicacion, estado);
    }
}
