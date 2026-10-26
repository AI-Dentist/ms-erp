package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.command.CrearPacienteCommand;

import java.time.LocalDate;

public record CrearPacienteInput(String ciNit, String nombres, String apellidos, LocalDate fechaNacimiento,
                                 String genero, String telefonoWhatsapp, String email, String direccion) {

    CrearPacienteCommand toCommand() {
        return new CrearPacienteCommand(ciNit, nombres, apellidos, fechaNacimiento, genero, telefonoWhatsapp, email,
                direccion);
    }
}
