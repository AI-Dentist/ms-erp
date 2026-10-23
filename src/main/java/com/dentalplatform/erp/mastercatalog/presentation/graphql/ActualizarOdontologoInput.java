package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.command.ActualizarOdontologoCommand;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record ActualizarOdontologoInput(String ci, String matriculaProfesional, String nombres, String apellidos,
                                        String telefono, String email, BigDecimal porcentajeComision,
                                        List<UUID> especialidadesIds) {

    ActualizarOdontologoCommand toCommand(UUID id) {
        return new ActualizarOdontologoCommand(id, ci, matriculaProfesional, nombres, apellidos, telefono, email,
                porcentajeComision, especialidadesIds);
    }
}
