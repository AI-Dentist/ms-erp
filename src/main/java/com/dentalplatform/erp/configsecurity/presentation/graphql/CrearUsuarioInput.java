package com.dentalplatform.erp.configsecurity.presentation.graphql;

import com.dentalplatform.erp.configsecurity.application.command.CrearUsuarioCommand;

public record CrearUsuarioInput(String rolCodigo, String username, String email, String nombreCompleto,
                                String password) {

    CrearUsuarioCommand toCommand() {
        return new CrearUsuarioCommand(rolCodigo, username, email, nombreCompleto, password);
    }

    @Override
    public String toString() {
        return "CrearUsuarioInput[rolCodigo=" + rolCodigo + ", username=" + username + ", email=" + email + "]";
    }
}
