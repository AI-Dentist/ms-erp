package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "audit_logs")
public class AuditLogJpaEntity {

    @Id
    private UUID id;

    @Column(name = "usuario_id")
    private UUID usuarioId;

    @Column(nullable = false, length = 100)
    private String accion;

    @Column(name = "entidad_afectada", nullable = false, length = 80)
    private String entidadAfectada;

    @Column(name = "entidad_id")
    private UUID entidadId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "detalle_json", columnDefinition = "jsonb")
    private String detalleJson;

    @Column(name = "ip_origen", length = 45)
    private String ipOrigen;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected AuditLogJpaEntity() {
    }

    public AuditLogJpaEntity(UUID id, UUID usuarioId, String accion, String entidadAfectada, UUID entidadId,
                             String detalleJson, String ipOrigen, OffsetDateTime createdAt) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.accion = accion;
        this.entidadAfectada = entidadAfectada;
        this.entidadId = entidadId;
        this.detalleJson = detalleJson;
        this.ipOrigen = ipOrigen;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public String getAccion() {
        return accion;
    }

    public String getEntidadAfectada() {
        return entidadAfectada;
    }

    public UUID getEntidadId() {
        return entidadId;
    }

    public String getDetalleJson() {
        return detalleJson;
    }

    public String getIpOrigen() {
        return ipOrigen;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
