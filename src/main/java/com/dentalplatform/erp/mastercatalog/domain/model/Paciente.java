package com.dentalplatform.erp.mastercatalog.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public class Paciente {

    private final UUID id;
    private final String ciNit;
    private final String nombres;
    private final String apellidos;
    private final LocalDate fechaNacimiento;
    private final String genero;
    private final String telefonoWhatsapp;
    private final String email;
    private final String direccion;
    private final boolean activo;
    private final OffsetDateTime createdAt;

    public Paciente(UUID id, String ciNit, String nombres, String apellidos, LocalDate fechaNacimiento, String genero,
                    String telefonoWhatsapp, String email, String direccion, boolean activo, OffsetDateTime createdAt) {
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

    public static Paciente crear(String ciNit, String nombres, String apellidos, LocalDate fechaNacimiento,
                                 String genero, String telefonoWhatsapp, String email, String direccion,
                                 OffsetDateTime now) {
        return new Paciente(UUID.randomUUID(), ciNit, nombres, apellidos, fechaNacimiento, genero, telefonoWhatsapp,
                email, direccion, true, now).normalizado(now);
    }

    /** Replaces every editable field (same rules as creation); identity, state and creation time are kept. */
    public Paciente actualizar(String ciNit, String nombres, String apellidos, LocalDate fechaNacimiento,
                               String genero, String telefonoWhatsapp, String email, String direccion,
                               OffsetDateTime now) {
        return new Paciente(id, ciNit, nombres, apellidos, fechaNacimiento, genero, telefonoWhatsapp, email, direccion,
                activo, createdAt).normalizado(now);
    }

    /** Logical deactivation; blocked while the patient still has unpaid (PENDIENTE or PARCIAL) charge orders. */
    public Paciente desactivar(long ordenesAbiertas) {
        if (ordenesAbiertas > 0) {
            throw new BusinessRuleException("No se puede desactivar el paciente: tiene " + ordenesAbiertas
                    + " orden(es) de cobro PENDIENTE o PARCIAL");
        }
        return new Paciente(id, ciNit, nombres, apellidos, fechaNacimiento, genero, telefonoWhatsapp, email, direccion,
                false, createdAt);
    }

    private Paciente normalizado(OffsetDateTime now) {
        if (fechaNacimiento != null && fechaNacimiento.isAfter(now.toLocalDate())) {
            throw new BusinessRuleException("La fecha de nacimiento no puede ser futura");
        }
        return new Paciente(id, Validaciones.requerido(ciNit, "ciNit"), Validaciones.requerido(nombres, "nombres"),
                Validaciones.requerido(apellidos, "apellidos"), fechaNacimiento, Validaciones.opcional(genero),
                Validaciones.requerido(telefonoWhatsapp, "telefonoWhatsapp"), Validaciones.emailOpcional(email),
                Validaciones.opcional(direccion), activo, createdAt);
    }

    public String nombreCompleto() {
        return nombres + " " + apellidos;
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
