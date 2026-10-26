package com.dentalplatform.erp.configsecurity.domain.model;

import java.util.List;
import java.util.UUID;

public record Rol(UUID id, String codigo, String nombre, String descripcion, List<Permiso> permisos) {

    public Rol {
        permisos = permisos == null ? List.of() : List.copyOf(permisos);
    }

    public List<String> codigosPermisos() {
        return permisos.stream().map(Permiso::codigo).sorted().toList();
    }
}
