package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "permisos")
public class PermisoJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 80)
    private String codigo;

    @Column(nullable = false, length = 50)
    private String modulo;

    private String descripcion;

    protected PermisoJpaEntity() {
    }

    public UUID getId() {
        return id;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getModulo() {
        return modulo;
    }

    public String getDescripcion() {
        return descripcion;
    }
}
