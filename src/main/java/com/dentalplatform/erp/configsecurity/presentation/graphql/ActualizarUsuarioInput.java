package com.dentalplatform.erp.configsecurity.presentation.graphql;

import com.dentalplatform.erp.configsecurity.application.command.ActualizarUsuarioCommand;

import java.util.UUID;

public record ActualizarUsuarioInput(String rolCodigo, boolean activo, String nombreCompleto, String email) {

    ActualizarUsuarioCommand toCommand(UUID id) {
        return new ActualizarUsuarioCommand(id, rolCodigo, activo, nombreCompleto, email);
    }
}
