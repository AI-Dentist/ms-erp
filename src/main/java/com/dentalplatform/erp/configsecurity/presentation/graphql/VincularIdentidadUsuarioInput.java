package com.dentalplatform.erp.configsecurity.presentation.graphql;

import java.util.UUID;

public record VincularIdentidadUsuarioInput(UUID usuarioId, String keycloakSubject) {
}
