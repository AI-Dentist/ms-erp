package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "usuarios")
public class UsuarioJpaEntity {

    @Id
    private UUID id;

    // Ordinary aggregate merges must not erase a link established after their snapshot was read.
    // Existing rows receive identity only through the conditional provisioning update.
    @Column(name = "keycloak_id", unique = true, length = 100, updatable = false)
    private String keycloakId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "rol_id", nullable = false)
    private RolJpaEntity rol;

    @Column(nullable = false, unique = true, length = 80)
    private String username;

    @Column(name = "password_hash")
    private String passwordHash;

    @Column(nullable = false, unique = true, length = 120)
    private String email;

    @Column(name = "nombre_completo", nullable = false, length = 150)
    private String nombreCompleto;

    private boolean activo;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected UsuarioJpaEntity() {
    }

    public UsuarioJpaEntity(UUID id, String keycloakId, RolJpaEntity rol, String username, String passwordHash,
                            String email, String nombreCompleto, boolean activo, OffsetDateTime createdAt) {
        this.id = id;
        this.keycloakId = keycloakId;
        this.rol = rol;
        this.username = username;
        this.passwordHash = passwordHash;
        this.email = email;
        this.nombreCompleto = nombreCompleto;
        this.activo = activo;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getKeycloakId() {
        return keycloakId;
    }

    public RolJpaEntity getRol() {
        return rol;
    }

    public String getUsername() {
        return username;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getEmail() {
        return email;
    }

    public String getNombreCompleto() {
        return nombreCompleto;
    }

    public boolean isActivo() {
        return activo;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
