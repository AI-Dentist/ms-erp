package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.command.CrearOdontologoCommand;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record CrearOdontologoInput(String ci, String matriculaProfesional, String nombres, String apellidos,
                                   String telefono, String email, BigDecimal porcentajeComision,
                                   List<UUID> especialidadesIds) {

    CrearOdontologoCommand toCommand() {
        return new CrearOdontologoCommand(ci, matriculaProfesional, nombres, apellidos, telefono, email,
                porcentajeComision, especialidadesIds);
    }
}
