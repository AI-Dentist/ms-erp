package com.dentalplatform.erp.mastercatalog.application.command;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CrearOdontologoCommand(String ci, String matriculaProfesional, String nombres, String apellidos,
                                     String telefono, String email, BigDecimal porcentajeComision,
                                     List<UUID> especialidadesIds) {
}
