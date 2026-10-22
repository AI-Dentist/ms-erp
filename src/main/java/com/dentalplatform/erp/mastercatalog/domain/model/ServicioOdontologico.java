package com.dentalplatform.erp.mastercatalog.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public class ServicioOdontologico {

    private final UUID id;
    private final Especialidad especialidad;
    private final String codigo;
    private final String nombre;
    private final String descripcion;
    private final Money precioBase;
    private final boolean activo;
    private final OffsetDateTime createdAt;

    public ServicioOdontologico(UUID id, Especialidad especialidad, String codigo, String nombre, String descripcion,
                                Money precioBase, boolean activo, OffsetDateTime createdAt) {
        this.id = id;
        this.especialidad = especialidad;
        this.codigo = codigo;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.precioBase = precioBase;
        this.activo = activo;
        this.createdAt = createdAt;
    }

    public static ServicioOdontologico crear(Especialidad especialidad, String codigo, String nombre,
                                             String descripcion, BigDecimal precioBaseBs, OffsetDateTime now) {
        if (precioBaseBs == null) {
            throw new BusinessRuleException("El campo precioBaseBs es obligatorio");
        }
        if (precioBaseBs.signum() < 0) {
            throw new BusinessRuleException("El precio base no puede ser negativo");
        }
        return new ServicioOdontologico(UUID.randomUUID(), especialidad, Validaciones.codigo(codigo, "codigo"),
                Validaciones.requerido(nombre, "nombre"), Validaciones.opcional(descripcion),
                Money.of(precioBaseBs), true, now);
    }

    public UUID getId() {
        return id;
    }

    public Especialidad getEspecialidad() {
        return especialidad;
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

    public Money getPrecioBase() {
        return precioBase;
    }

    public boolean isActivo() {
        return activo;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
