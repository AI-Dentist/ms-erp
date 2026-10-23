package com.dentalplatform.erp.mastercatalog.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class Consultorio {

    public static final String ESTADO_DISPONIBLE = "DISPONIBLE";
    public static final String ESTADO_MANTENIMIENTO = "MANTENIMIENTO";
    /** There is no activo column: INACTIVO is the logical-deactivation state. */
    public static final String ESTADO_INACTIVO = "INACTIVO";
    public static final List<String> ESTADOS = List.of(ESTADO_DISPONIBLE, ESTADO_MANTENIMIENTO, ESTADO_INACTIVO);

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

    /** Replaces the editable fields; a null estado keeps the current one, any other must be in {@link #ESTADOS}. */
    public Consultorio actualizar(String codigo, String nombre, String ubicacion, String estado) {
        return new Consultorio(id, Validaciones.codigo(codigo, "codigo"), Validaciones.requerido(nombre, "nombre"),
                Validaciones.opcional(ubicacion), estado == null ? this.estado : estadoValido(estado), createdAt);
    }

    public Consultorio desactivar() {
        return new Consultorio(id, codigo, nombre, ubicacion, ESTADO_INACTIVO, createdAt);
    }

    public boolean isActivo() {
        return !ESTADO_INACTIVO.equals(estado);
    }

    private static String estadoValido(String estado) {
        String normalizado = estado.trim().toUpperCase(Locale.ROOT);
        if (!ESTADOS.contains(normalizado)) {
            throw new BusinessRuleException("Estado de consultorio no válido: " + estado.trim()
                    + ". Valores permitidos: " + String.join(", ", ESTADOS));
        }
        return normalizado;
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
