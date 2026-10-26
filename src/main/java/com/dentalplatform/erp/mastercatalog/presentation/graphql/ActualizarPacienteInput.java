package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.command.ActualizarPacienteCommand;

import java.time.LocalDate;
import java.util.UUID;

public record ActualizarPacienteInput(String ciNit, String nombres, String apellidos, LocalDate fechaNacimiento,
                                      String genero, String telefonoWhatsapp, String email, String direccion) {

    ActualizarPacienteCommand toCommand(UUID id) {
        return new ActualizarPacienteCommand(id, ciNit, nombres, apellidos, fechaNacimiento, genero,
                telefonoWhatsapp, email, direccion);
    }
}
