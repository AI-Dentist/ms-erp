package com.dentalplatform.erp.configsecurity.presentation.graphql;

import java.util.UUID;

public record CrearUsuarioConIdentidadInput(CrearUsuarioInput usuario, String keycloakSubject, UUID odontologoId) {
    @Override
    public String toString() { return "CrearUsuarioConIdentidadInput[redacted]"; }
}
