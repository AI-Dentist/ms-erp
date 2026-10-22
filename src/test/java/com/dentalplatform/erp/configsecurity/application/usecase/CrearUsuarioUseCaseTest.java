package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.application.command.CrearUsuarioCommand;
import com.dentalplatform.erp.configsecurity.application.port.PasswordHasherPort;
import com.dentalplatform.erp.configsecurity.domain.model.Rol;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.RolRepository;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CrearUsuarioUseCaseTest {

    private static final Rol CAJERO = new Rol(UUID.randomUUID(), "CAJERO", "Cajero", null, List.of());

    private UsuarioRepository usuarios;
    private RolRepository roles;
    private PasswordHasherPort hasher;
    private RegistrarAuditoriaUseCase auditoria;
    private CrearUsuarioUseCase useCase;

    @BeforeEach
    void setUp() {
        usuarios = mock(UsuarioRepository.class);
        roles = mock(RolRepository.class);
        hasher = mock(PasswordHasherPort.class);
        auditoria = mock(RegistrarAuditoriaUseCase.class);
        useCase = new CrearUsuarioUseCase(usuarios, roles, hasher, auditoria,
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
        when(roles.buscarPorCodigo("CAJERO")).thenReturn(Optional.of(CAJERO));
        when(hasher.hash(anyString())).thenReturn("$2a$10$hash");
        when(usuarios.guardar(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createsActiveUserWithHashedPasswordAndAuditsIt() {
        Usuario creado = useCase.ejecutar(command("Caja.Melgar", "Caja@SonrisaCrucena.bo", "Segura2026"));

        ArgumentCaptor<Usuario> saved = ArgumentCaptor.forClass(Usuario.class);
        verify(usuarios).guardar(saved.capture());
        assertThat(saved.getValue().getPasswordHash()).isEqualTo("$2a$10$hash");
        assertThat(creado.getUsername()).isEqualTo("caja.melgar");
        assertThat(creado.getEmail()).isEqualTo("caja@sonrisacrucena.bo");
        assertThat(creado.getRol()).isEqualTo(CAJERO);
        assertThat(creado.isActivo()).isTrue();
        verify(hasher).hash("Segura2026");
        verify(auditoria).registrar(eq("USUARIO_CREADO"), eq("usuarios"), eq(creado.getId()), anyMap());
    }

    @Test
    void neverAuditsThePassword() {
        ArgumentCaptor<Map<String, Object>> detalle = ArgumentCaptor.captor();
        useCase.ejecutar(command("caja2", "c2@x.bo", "Segura2026"));

        verify(auditoria).registrar(anyString(), anyString(), any(), detalle.capture());
        assertThat(detalle.getValue().toString()).doesNotContain("Segura2026").doesNotContain("$2a$");
    }

    @Test
    void rejectsUnknownRole() {
        when(roles.buscarPorCodigo("SUPERUSER")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.ejecutar(new CrearUsuarioCommand("SUPERUSER", "x.y", "x@y.bo", "X Y", "Segura2026")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("SUPERUSER");
        verify(usuarios, never()).guardar(any());
    }

    @Test
    void rejectsDuplicatedUsername() {
        when(usuarios.existeUsername("caja.melgar")).thenReturn(true);

        assertThatThrownBy(() -> useCase.ejecutar(command("Caja.Melgar", "otro@x.bo", "Segura2026")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("username");
        verify(usuarios, never()).guardar(any());
        verifyNoInteractions(auditoria);
    }

    @Test
    void rejectsDuplicatedEmail() {
        when(usuarios.existeEmail("dup@x.bo")).thenReturn(true);

        assertThatThrownBy(() -> useCase.ejecutar(command("nuevo", "DUP@x.bo", "Segura2026")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("email");
        verify(usuarios, never()).guardar(any());
    }

    @Test
    void rejectsShortPasswordBeforeHashing() {
        assertThatThrownBy(() -> useCase.ejecutar(command("nuevo", "n@x.bo", "corta")))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("8");
        verifyNoInteractions(hasher);
        verify(usuarios, never()).guardar(any());
    }

    @Test
    void rejectsInvalidEmail() {
        assertThatThrownBy(() -> useCase.ejecutar(command("nuevo", "no-es-email", "Segura2026")))
                .isInstanceOf(BusinessRuleException.class);
        verify(usuarios, never()).guardar(any());
    }

    private static CrearUsuarioCommand command(String username, String email, String password) {
        return new CrearUsuarioCommand("CAJERO", username, email, "Ana Melgar", password);
    }
}
