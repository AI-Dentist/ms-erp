package com.dentalplatform.erp.mastercatalog.domain.model;

import java.time.OffsetDateTime;
import java.util.UUID;

public class Consultorio {

    public static final String ESTADO_DISPONIBLE = "DISPONIBLE";

    private final UUID id;
    private final String codigo;
    private final String nombre;
    private final String ubicacion;
    private final String estado;
    private final OffsetDateTime createdAt;

    public Consultorio(UUID id, String codigo, String nombre, String ubicacion, String estado,
                       OffsetDateTime createdAt) {
        this.id = id;
        this.codigo = codigo;
        this.nombre = nombre;
        this.ubicacion = ubicacion;
        this.estado = estado == null ? ESTADO_DISPONIBLE : estado;
        this.createdAt = createdAt;
    }

    public static Consultorio crear(String codigo, String nombre, String ubicacion, OffsetDateTime now) {
        return new Consultorio(UUID.randomUUID(), Validaciones.codigo(codigo, "codigo"),
                Validaciones.requerido(nombre, "nombre"), Validaciones.opcional(ubicacion), ESTADO_DISPONIBLE, now);
    }

    public UUID getId() {
        return id;
    }

    public String getCodigo() {
        return codigo;
    }

    public String getNombre() {
        return nombre;
    }

    public String getUbicacion() {
        return ubicacion;
    }

    public String getEstado() {
        return estado;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
