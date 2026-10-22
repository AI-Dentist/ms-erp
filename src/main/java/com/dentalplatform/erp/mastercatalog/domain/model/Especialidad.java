package com.dentalplatform.erp.mastercatalog.domain.model;

import java.util.UUID;

public class Especialidad {

    private final UUID id;
    private final String codigo;
    private final String nombre;
    private final String descripcion;
    private final boolean activo;

    public Especialidad(UUID id, String codigo, String nombre, String descripcion, boolean activo) {
        this.id = id;
        this.codigo = codigo;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.activo = activo;
    }

    public static Especialidad crear(String codigo, String nombre, String descripcion) {
        return new Especialidad(UUID.randomUUID(), Validaciones.codigo(codigo, "codigo"),
                Validaciones.requerido(nombre, "nombre"), Validaciones.opcional(descripcion), true);
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

    public String getDescripcion() {
        return descripcion;
    }

    public boolean isActivo() {
        return activo;
    }
}
