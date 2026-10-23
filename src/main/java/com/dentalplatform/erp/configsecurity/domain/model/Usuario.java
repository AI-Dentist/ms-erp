package com.dentalplatform.erp.configsecurity.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;

public class Usuario {

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Pattern USERNAME = Pattern.compile("^[a-z0-9._-]{3,80}$");
    public static final int MIN_PASSWORD_LENGTH = 8;
    public static final String ROL_ADMIN = "ADMIN";

    private final UUID id;
    private final String keycloakId;
    private final Rol rol;
    private final String username;
    private final String passwordHash;
    private final String email;
    private final String nombreCompleto;
    private final boolean activo;
    private final OffsetDateTime createdAt;

    public Usuario(UUID id, String keycloakId, Rol rol, String username, String passwordHash, String email,
                   String nombreCompleto, boolean activo, OffsetDateTime createdAt) {
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

    /**
     * Creates an active user. The raw password is validated here but only its hash is kept.
     */
    public static Usuario crear(Rol rol, String username, String rawPassword, String passwordHash, String email,
                                String nombreCompleto, OffsetDateTime now) {
        if (rol == null) {
            throw new BusinessRuleException("El rol es obligatorio");
        }
        String normalizedUsername = EmpresaConfig.requerido(username, "username").toLowerCase();
        if (!USERNAME.matcher(normalizedUsername).matches()) {
            throw new BusinessRuleException("El username solo admite minúsculas, dígitos, '.', '_' o '-' (3 a 80)");
        }
        String normalizedEmail = EmpresaConfig.requerido(email, "email").toLowerCase();
        if (!EMAIL.matcher(normalizedEmail).matches()) {
            throw new BusinessRuleException("El email no es válido: " + email);
        }
        validarPassword(rawPassword);
        return new Usuario(UUID.randomUUID(), null, rol, normalizedUsername, passwordHash, normalizedEmail,
                EmpresaConfig.requerido(nombreCompleto, "nombreCompleto"), true, now);
    }

    /**
     * Changes role, active flag and, when provided, name and email. Username and credentials never change.
     *
     * @param actorId            user performing the change, null when it is not a registered user
     * @param otrosAdminsActivos active administrators other than this user, read under lock by the caller
     */
    public Usuario actualizar(UUID actorId, Rol nuevoRol, boolean nuevoActivo, String nuevoNombreCompleto,
                              String nuevoEmail, long otrosAdminsActivos) {
        if (nuevoRol == null) {
            throw new BusinessRuleException("El rol es obligatorio");
        }
        boolean esAdminActivo = activo && ROL_ADMIN.equals(rol.codigo());
        boolean seguiraAdminActivo = nuevoActivo && ROL_ADMIN.equals(nuevoRol.codigo());
        boolean esElMismo = id.equals(actorId);
        if (esElMismo && activo && !nuevoActivo) {
            throw new BusinessRuleException("Un usuario no puede desactivarse a sí mismo");
        }
        if (esElMismo && ROL_ADMIN.equals(rol.codigo()) && !ROL_ADMIN.equals(nuevoRol.codigo())) {
            throw new BusinessRuleException("Un administrador no puede quitarse a sí mismo el rol ADMIN");
        }
        if (esAdminActivo && !seguiraAdminActivo && otrosAdminsActivos < 1) {
            throw new BusinessRuleException("El sistema debe conservar al menos un administrador activo");
        }
        String email = this.email;
        if (nuevoEmail != null) {
            email = EmpresaConfig.requerido(nuevoEmail, "email").toLowerCase();
            if (!EMAIL.matcher(email).matches()) {
                throw new BusinessRuleException("El email no es válido: " + nuevoEmail);
            }
        }
        String nombre = nuevoNombreCompleto == null ? nombreCompleto
                : EmpresaConfig.requerido(nuevoNombreCompleto, "nombreCompleto");
        return new Usuario(id, keycloakId, nuevoRol, username, passwordHash, email, nombre, nuevoActivo, createdAt);
    }

    public static void validarPassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new BusinessRuleException("La contraseña debe tener al menos " + MIN_PASSWORD_LENGTH + " caracteres");
        }
    }

    public UUID getId() {
        return id;
    }

    public String getKeycloakId() {
        return keycloakId;
    }

    public Rol getRol() {
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
