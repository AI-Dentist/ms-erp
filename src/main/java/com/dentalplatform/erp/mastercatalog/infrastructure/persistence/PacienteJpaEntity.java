package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "pacientes")
public class PacienteJpaEntity {

    @Id
    private UUID id;

    @Column(name = "ci_nit", nullable = false, unique = true, length = 30)
    private String ciNit;

    @Column(nullable = false, length = 100)
    private String nombres;

    @Column(nullable = false, length = 100)
    private String apellidos;

    @Column(name = "fecha_nacimiento")
    private LocalDate fechaNacimiento;

    @Column(length = 20)
    private String genero;

    @Column(name = "telefono_whatsapp", nullable = false, length = 30)
    private String telefonoWhatsapp;

    @Column(length = 120)
    private String email;

    private String direccion;

    private boolean activo;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    protected PacienteJpaEntity() {
    }

    public PacienteJpaEntity(UUID id, String ciNit, String nombres, String apellidos, LocalDate fechaNacimiento,
                             String genero, String telefonoWhatsapp, String email, String direccion, boolean activo,
                             OffsetDateTime createdAt) {
        this.id = id;
        this.ciNit = ciNit;
        this.nombres = nombres;
        this.apellidos = apellidos;
        this.fechaNacimiento = fechaNacimiento;
        this.genero = genero;
        this.telefonoWhatsapp = telefonoWhatsapp;
        this.email = email;
        this.direccion = direccion;
        this.activo = activo;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getCiNit() {
        return ciNit;
    }

    public String getNombres() {
        return nombres;
    }

    public String getApellidos() {
        return apellidos;
    }

    public LocalDate getFechaNacimiento() {
        return fechaNacimiento;
    }

    public String getGenero() {
        return genero;
    }

    public String getTelefonoWhatsapp() {
        return telefonoWhatsapp;
    }

    public String getEmail() {
        return email;
    }

    public String getDireccion() {
        return direccion;
    }

    public boolean isActivo() {
        return activo;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
