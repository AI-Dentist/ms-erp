package com.dentalplatform.erp.mastercatalog.application.command;

import java.time.LocalDate;
import java.util.UUID;

public record ActualizarPacienteCommand(UUID id, String ciNit, String nombres, String apellidos,
                                        LocalDate fechaNacimiento, String genero, String telefonoWhatsapp,
                                        String email, String direccion) {
}
