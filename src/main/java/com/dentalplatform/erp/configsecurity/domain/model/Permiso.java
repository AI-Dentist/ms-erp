package com.dentalplatform.erp.configsecurity.domain.model;

import java.util.UUID;

public record Permiso(UUID id, String codigo, String modulo, String descripcion) {
}
