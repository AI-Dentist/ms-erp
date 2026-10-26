package com.dentalplatform.erp.configsecurity.application.command;

/** A null nombreCompleto or email keeps the current value. */
public record ActualizarUsuarioCommand(java.util.UUID id, String rolCodigo, boolean activo, String nombreCompleto,
                                       String email) {
}
