package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import java.util.UUID;

public record VincularIdentidadOdontologoInput(UUID usuarioId, UUID odontologoId, String keycloakSubject) {
}
