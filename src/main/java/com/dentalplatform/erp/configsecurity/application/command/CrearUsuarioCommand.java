package com.dentalplatform.erp.configsecurity.application.command;

public record CrearUsuarioCommand(String rolCodigo, String username, String email, String nombreCompleto,
                                  String password) {

    @Override
    public String toString() {
        return "CrearUsuarioCommand[rolCodigo=" + rolCodigo + ", username=" + username + ", email=" + email + "]";
    }
}
