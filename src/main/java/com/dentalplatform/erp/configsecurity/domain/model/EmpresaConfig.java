package com.dentalplatform.erp.configsecurity.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Single-row aggregate with the clinic's legal and contact data printed on receipts.
 */
public class EmpresaConfig {

    private final UUID id;
    private String nombreComercial;
    private String razonSocial;
    private String nit;
    private String direccion;
    private String telefonoContacto;
    private String emailContacto;
    private String logoUrl;
    private String monedaSimbolo;
    private OffsetDateTime updatedAt;

    public EmpresaConfig(UUID id, String nombreComercial, String razonSocial, String nit, String direccion,
                         String telefonoContacto, String emailContacto, String logoUrl, String monedaSimbolo,
                         OffsetDateTime updatedAt) {
        this.id = id;
        this.nombreComercial = nombreComercial;
        this.razonSocial = razonSocial;
        this.nit = nit;
        this.direccion = direccion;
        this.telefonoContacto = telefonoContacto;
        this.emailContacto = emailContacto;
        this.logoUrl = logoUrl;
        this.monedaSimbolo = monedaSimbolo == null ? "Bs" : monedaSimbolo;
        this.updatedAt = updatedAt;
    }

    public static EmpresaConfig nueva(OffsetDateTime now) {
        return new EmpresaConfig(UUID.randomUUID(), null, null, null, null, null, null, null, "Bs", now);
    }

    public void actualizar(String nombreComercial, String razonSocial, String nit, String direccion,
                           String telefonoContacto, String emailContacto, OffsetDateTime now) {
        this.nombreComercial = requerido(nombreComercial, "nombreComercial");
        this.razonSocial = requerido(razonSocial, "razonSocial");
        this.nit = requerido(nit, "nit");
        if (!this.nit.matches("\\d{5,20}")) {
            throw new BusinessRuleException("El NIT debe contener solo dígitos (5 a 20)");
        }
        this.direccion = requerido(direccion, "direccion");
        this.telefonoContacto = requerido(telefonoContacto, "telefonoContacto");
        this.emailContacto = emailContacto == null || emailContacto.isBlank() ? null : emailContacto.trim();
        this.updatedAt = now;
    }

    static String requerido(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new BusinessRuleException("El campo " + field + " es obligatorio");
        }
        return value.trim();
    }

    public UUID getId() {
        return id;
    }

    public String getNombreComercial() {
        return nombreComercial;
    }

    public String getRazonSocial() {
        return razonSocial;
    }

    public String getNit() {
        return nit;
    }

    public String getDireccion() {
        return direccion;
    }

    public String getTelefonoContacto() {
        return telefonoContacto;
    }

    public String getEmailContacto() {
        return emailContacto;
    }

    public String getLogoUrl() {
        return logoUrl;
    }

    public String getMonedaSimbolo() {
        return monedaSimbolo;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }
}
