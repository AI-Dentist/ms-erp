package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.command.CrearConsultorioCommand;

public record CrearConsultorioInput(String codigo, String nombre, String ubicacion) {

    CrearConsultorioCommand toCommand() {
        return new CrearConsultorioCommand(codigo, nombre, ubicacion);
    }
}
