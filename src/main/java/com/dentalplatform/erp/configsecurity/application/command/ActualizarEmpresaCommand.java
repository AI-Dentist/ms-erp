package com.dentalplatform.erp.configsecurity.application.command;

public record ActualizarEmpresaCommand(String nombreComercial, String razonSocial, String nit, String direccion,
                                       String telefonoContacto, String emailContacto) {
}
