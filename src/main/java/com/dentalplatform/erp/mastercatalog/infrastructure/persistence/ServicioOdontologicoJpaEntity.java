package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "servicios_odontologicos")
public class ServicioOdontologicoJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "especialidad_id")
    private EspecialidadJpaEntity especialidad;

    @Column(nullable = false, unique = true, length = 30)
    private String codigo;

    @Column(nullable = false, length = 150)
    private String nombre;

    private String descripcion;

    @Column(name = "precio_base_bs", nullable = false, precision = 12, scale = 2)
    private BigDecimal precioBaseBs;

    private boolean activo;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected ServicioOdontologicoJpaEntity() {
    }

    public ServicioOdontologicoJpaEntity(UUID id, EspecialidadJpaEntity especialidad, String codigo, String nombre,
                                         String descripcion, BigDecimal precioBaseBs, boolean activo,
                                         OffsetDateTime createdAt) {
        this.id = id;
        this.especialidad = especialidad;
        this.codigo = codigo;
        this.nombre = nombre;
        this.descripcion = descripcion;
        this.precioBaseBs = precioBaseBs;
        this.activo = activo;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public EspecialidadJpaEntity getEspecialidad() {
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

    public BigDecimal getPrecioBaseBs() {
        return precioBaseBs;
    }

    public boolean isActivo() {
        return activo;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
