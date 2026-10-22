package com.dentalplatform.erp.mastercatalog.application.command;

import java.time.LocalDate;

public record CrearPacienteCommand(String ciNit, String nombres, String apellidos, LocalDate fechaNacimiento,
                                   String genero, String telefonoWhatsapp, String email, String direccion) {
}
