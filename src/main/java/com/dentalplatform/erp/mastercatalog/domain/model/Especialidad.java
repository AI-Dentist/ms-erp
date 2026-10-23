package com.dentalplatform.erp.mastercatalog.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

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

    public Especialidad actualizar(String codigo, String nombre, String descripcion) {
        return new Especialidad(id, Validaciones.codigo(codigo, "codigo"), Validaciones.requerido(nombre, "nombre"),
                Validaciones.opcional(descripcion), activo);
    }

    /** Logical deactivation; blocked while active services or active dentists still reference the specialty. */
    public Especialidad desactivar(long serviciosActivos, long odontologosActivos) {
        if (serviciosActivos > 0 || odontologosActivos > 0) {
            StringBuilder uso = new StringBuilder();
            if (serviciosActivos > 0) {
                uso.append(serviciosActivos).append(" servicio(s) activo(s)");
            }
            if (odontologosActivos > 0) {
                uso.append(uso.isEmpty() ? "" : " y ").append(odontologosActivos).append(" odontólogo(s) activo(s)");
            }
            throw new BusinessRuleException("No se puede desactivar la especialidad: la usan " + uso);
        }
        return new Especialidad(id, codigo, nombre, descripcion, false);
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
