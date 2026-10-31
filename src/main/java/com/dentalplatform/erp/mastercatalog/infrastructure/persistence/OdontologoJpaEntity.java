package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "odontologos")
public class OdontologoJpaEntity {

    @Id
    private UUID id;

    // Identity is written separately so stale catalog updates/deactivations cannot erase a new link.
    @Column(name = "usuario_id", unique = true, updatable = false)
    private UUID usuarioId;

    @Column(nullable = false, unique = true, length = 25)
    private String ci;

    @Column(name = "matricula_profesional", nullable = false, unique = true, length = 50)
    private String matriculaProfesional;

    @Column(nullable = false, length = 100)
    private String nombres;

    @Column(nullable = false, length = 100)
    private String apellidos;

    @Column(nullable = false, length = 30)
    private String telefono;

    @Column(length = 120)
    private String email;

    @Column(name = "porcentaje_comision", precision = 5, scale = 2)
    private BigDecimal porcentajeComision;

    private boolean activo;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "odontologo_especialidades",
            joinColumns = @JoinColumn(name = "odontologo_id"),
            inverseJoinColumns = @JoinColumn(name = "especialidad_id"))
    private Set<EspecialidadJpaEntity> especialidades = new LinkedHashSet<>();

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected OdontologoJpaEntity() {
    }

    public OdontologoJpaEntity(UUID id, UUID usuarioId, String ci, String matriculaProfesional, String nombres,
                               String apellidos, String telefono, String email, BigDecimal porcentajeComision,
                               boolean activo, Set<EspecialidadJpaEntity> especialidades, OffsetDateTime createdAt) {
        this.id = id;
        this.usuarioId = usuarioId;
        this.ci = ci;
        this.matriculaProfesional = matriculaProfesional;
        this.nombres = nombres;
        this.apellidos = apellidos;
        this.telefono = telefono;
        this.email = email;
        this.porcentajeComision = porcentajeComision;
        this.activo = activo;
        this.especialidades = especialidades;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getUsuarioId() {
        return usuarioId;
    }

    public String getCi() {
        return ci;
    }

    public String getMatriculaProfesional() {
        return matriculaProfesional;
    }

    public String getNombres() {
        return nombres;
    }

    public String getApellidos() {
        return apellidos;
    }

    public String getTelefono() {
        return telefono;
    }

    public String getEmail() {
        return email;
    }

    public BigDecimal getPorcentajeComision() {
        return porcentajeComision;
    }

    public boolean isActivo() {
        return activo;
    }

    public Set<EspecialidadJpaEntity> getEspecialidades() {
        return especialidades;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
