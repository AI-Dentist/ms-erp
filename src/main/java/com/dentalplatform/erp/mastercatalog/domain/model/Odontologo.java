package com.dentalplatform.erp.mastercatalog.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public class Odontologo {

    private static final BigDecimal MAX_COMISION = new BigDecimal("100");

    private final UUID id;
    private final UUID usuarioId;
    private final String ci;
    private final String matriculaProfesional;
    private final String nombres;
    private final String apellidos;
    private final String telefono;
    private final String email;
    private final BigDecimal porcentajeComision;
    private final boolean activo;
    private final List<Especialidad> especialidades;
    private final OffsetDateTime createdAt;

    public Odontologo(UUID id, UUID usuarioId, String ci, String matriculaProfesional, String nombres, String apellidos,
                      String telefono, String email, BigDecimal porcentajeComision, boolean activo,
                      List<Especialidad> especialidades, OffsetDateTime createdAt) {
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
        this.especialidades = List.copyOf(especialidades);
        this.createdAt = createdAt;
    }

    public static Odontologo crear(String ci, String matriculaProfesional, String nombres, String apellidos,
                                   String telefono, String email, BigDecimal porcentajeComision,
                                   List<Especialidad> especialidades, OffsetDateTime now) {
        if (porcentajeComision == null) {
            throw new BusinessRuleException("El campo porcentajeComision es obligatorio");
        }
        if (porcentajeComision.signum() < 0 || porcentajeComision.compareTo(MAX_COMISION) > 0) {
            throw new BusinessRuleException("El porcentaje de comisión debe estar entre 0 y 100");
        }
        return new Odontologo(UUID.randomUUID(), null, Validaciones.requerido(ci, "ci"),
                Validaciones.codigo(matriculaProfesional, "matriculaProfesional"),
                Validaciones.requerido(nombres, "nombres"), Validaciones.requerido(apellidos, "apellidos"),
                Validaciones.requerido(telefono, "telefono"), Validaciones.emailOpcional(email),
                porcentajeComision.setScale(2, RoundingMode.HALF_UP), true,
                especialidades == null ? List.of() : especialidades, now);
    }

    public String nombreCompleto() {
        return nombres + " " + apellidos;
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

    public List<Especialidad> getEspecialidades() {
        return especialidades;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
