package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "consultorios")
public class ConsultorioJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, unique = true, length = 20)
    private String codigo;

    @Column(nullable = false, length = 100)
    private String nombre;

    @Column(length = 150)
    private String ubicacion;

    @Column(length = 30)
    private String estado;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected ConsultorioJpaEntity() {
    }

    public ConsultorioJpaEntity(UUID id, String codigo, String nombre, String ubicacion, String estado,
                                OffsetDateTime createdAt) {
        this.id = id;
        this.codigo = codigo;
        this.nombre = nombre;
        this.ubicacion = ubicacion;
        this.estado = estado;
        this.createdAt = createdAt;
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
