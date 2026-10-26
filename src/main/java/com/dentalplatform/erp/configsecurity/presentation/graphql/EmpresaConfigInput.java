package com.dentalplatform.erp.configsecurity.presentation.graphql;

import com.dentalplatform.erp.configsecurity.application.command.ActualizarEmpresaCommand;

public record EmpresaConfigInput(String nombreComercial, String razonSocial, String nit, String direccion,
                                 String telefonoContacto, String emailContacto) {

    ActualizarEmpresaCommand toCommand() {
        return new ActualizarEmpresaCommand(nombreComercial, razonSocial, nit, direccion, telefonoContacto,
                emailContacto);
    }
}
