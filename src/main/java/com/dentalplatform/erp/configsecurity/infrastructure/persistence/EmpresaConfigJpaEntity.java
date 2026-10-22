package com.dentalplatform.erp.configsecurity.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "empresa_config")
public class EmpresaConfigJpaEntity {

    @Id
    private UUID id;

    @Column(name = "nombre_comercial", nullable = false, length = 150)
    private String nombreComercial;

    @Column(name = "razon_social", nullable = false, length = 150)
    private String razonSocial;

    @Column(nullable = false, length = 30)
    private String nit;

    @Column(nullable = false)
    private String direccion;

    @Column(name = "telefono_contacto", nullable = false, length = 30)
    private String telefonoContacto;

    @Column(name = "email_contacto", length = 100)
    private String emailContacto;

    @Column(name = "logo_url")
    private String logoUrl;

    @Column(name = "moneda_simbolo", length = 10)
    private String monedaSimbolo;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    protected EmpresaConfigJpaEntity() {
    }

    public EmpresaConfigJpaEntity(UUID id, String nombreComercial, String razonSocial, String nit, String direccion,
                                  String telefonoContacto, String emailContacto, String logoUrl,
                                  String monedaSimbolo, OffsetDateTime updatedAt) {
        this.id = id;
        this.nombreComercial = nombreComercial;
        this.razonSocial = razonSocial;
        this.nit = nit;
        this.direccion = direccion;
        this.telefonoContacto = telefonoContacto;
        this.emailContacto = emailContacto;
        this.logoUrl = logoUrl;
        this.monedaSimbolo = monedaSimbolo;
        this.updatedAt = updatedAt;
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
