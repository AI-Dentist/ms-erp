package com.dentalplatform.erp.configsecurity.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UsuarioActualizacionTest {

    private static final Rol ADMIN = new Rol(UUID.randomUUID(), "ADMIN", "Administrador", null, List.of());
    private static final Rol CAJERO = new Rol(UUID.randomUUID(), "CAJERO", "Cajero", null, List.of());
    private static final UUID OTRO = UUID.randomUUID();

    private static Usuario usuario(Rol rol, boolean activo) {
        return new Usuario(UUID.randomUUID(), null, rol, "jperez", "hash", "jperez@x.com", "Juan Pérez", activo,
                OffsetDateTime.parse("2026-10-21T12:00:00-04:00"));
    }

    @Test
    void actualizarChangesRoleNameEmailAndActiveKeepingIdentityAndCredentials() {
        Usuario u = usuario(CAJERO, true);

        Usuario editado = u.actualizar(OTRO, ADMIN, true, " Juan P. ", " JUAN@X.COM ", 0);

        assertThat(editado.getId()).isEqualTo(u.getId());
        assertThat(editado.getUsername()).isEqualTo("jperez");
        assertThat(editado.getPasswordHash()).isEqualTo("hash");
        assertThat(editado.getRol()).isEqualTo(ADMIN);
        assertThat(editado.getNombreCompleto()).isEqualTo("Juan P.");
        assertThat(editado.getEmail()).isEqualTo("juan@x.com");
        assertThat(u.getRol()).isEqualTo(CAJERO);
    }

    @Test
    void actualizarKeepsNameAndEmailWhenNotProvidedAndRejectsInvalidOnes() {
        Usuario u = usuario(CAJERO, true);

        Usuario editado = u.actualizar(OTRO, CAJERO, false, null, null, 0);

        assertThat(editado.getNombreCompleto()).isEqualTo("Juan Pérez");
        assertThat(editado.getEmail()).isEqualTo("jperez@x.com");
        assertThat(editado.isActivo()).isFalse();
        assertThatThrownBy(() -> u.actualizar(OTRO, CAJERO, true, null, "malo", 0))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("El email no es válido");
        assertThatThrownBy(() -> u.actualizar(OTRO, null, true, null, null, 0))
                .isInstanceOf(BusinessRuleException.class).hasMessage("El rol es obligatorio");
    }

    @Test
    void nobodyCanDeactivateThemselves() {
        Usuario u = usuario(CAJERO, true);

        assertThatThrownBy(() -> u.actualizar(u.getId(), CAJERO, false, null, null, 5))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Un usuario no puede desactivarse a sí mismo");
    }

    @Test
    void anAdminCannotRemoveTheirOwnAdminRoleEvenWhenOtherAdminsExist() {
        Usuario admin = usuario(ADMIN, true);

        assertThatThrownBy(() -> admin.actualizar(admin.getId(), CAJERO, true, null, null, 3))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Un administrador no puede quitarse a sí mismo el rol ADMIN");
        assertThat(admin.actualizar(admin.getId(), ADMIN, true, "Nuevo Nombre", null, 0).getNombreCompleto())
                .isEqualTo("Nuevo Nombre");
    }

    @Test
    void theLastActiveAdminCannotBeDeactivatedOrDemotedByAnotherActor() {
        Usuario admin = usuario(ADMIN, true);

        assertThatThrownBy(() -> admin.actualizar(OTRO, ADMIN, false, null, null, 0))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El sistema debe conservar al menos un administrador activo");
        assertThatThrownBy(() -> admin.actualizar(OTRO, CAJERO, true, null, null, 0))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El sistema debe conservar al menos un administrador activo");
        assertThat(admin.actualizar(OTRO, CAJERO, true, null, null, 1).getRol()).isEqualTo(CAJERO);
        assertThat(admin.actualizar(OTRO, ADMIN, false, null, null, 1).isActivo()).isFalse();
    }

    @Test
    void inactiveOrNonAdminUsersAreNotSubjectToTheLastAdminRule() {
        assertThat(usuario(CAJERO, true).actualizar(OTRO, CAJERO, false, null, null, 0).isActivo()).isFalse();
        assertThat(usuario(ADMIN, false).actualizar(OTRO, CAJERO, false, null, null, 0).getRol()).isEqualTo(CAJERO);
    }
}
