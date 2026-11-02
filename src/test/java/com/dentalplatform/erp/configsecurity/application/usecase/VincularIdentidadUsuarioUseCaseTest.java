package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class VincularIdentidadUsuarioUseCaseTest {
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);
    private final RegistrarAuditoriaUseCase audit = mock(RegistrarAuditoriaUseCase.class);
    private final VincularIdentidadUsuarioUseCase useCase = new VincularIdentidadUsuarioUseCase(usuarios, audit);
    private final UUID id = UUID.randomUUID();

    private Usuario target(String subject, boolean active) {
        var user = mock(Usuario.class);
        when(user.getId()).thenReturn(id);
        when(user.isActivo()).thenReturn(active);
        when(user.getKeycloakId()).thenReturn(subject);
        when(usuarios.bloquearPorId(id)).thenReturn(Optional.of(user));
        return user;
    }

    @Test
    void invalidSubjectsRejectBeforeLockOrAudit() {
        for (String subject : new String[]{null, "", "   ", "😀".repeat(101)}) {
            assertThatThrownBy(() -> useCase.ejecutar(id, subject)).isInstanceOf(BusinessRuleException.class)
                    .hasMessage("El subject de Keycloak debe tener entre 1 y 100 caracteres y no estar en blanco");
        }
        verifyNoInteractions(usuarios, audit);
    }

    @Test
    void newLinkUsesExistingLockConditionalBindThenAuditInOrder() {
        target(null, true);
        String subject = "😀".repeat(100);
        assertThat(useCase.ejecutar(id, subject)).isEqualTo(id);
        var order = inOrder(usuarios, audit);
        order.verify(usuarios).bloquearPorId(id);
        order.verify(usuarios).buscarPorKeycloakId(subject);
        order.verify(usuarios).vincularKeycloakId(id, subject);
        order.verify(audit).registrar("IDENTIDAD_USUARIO_VINCULADA", "usuarios", id, Map.of("keycloakSubject", subject));
        verifyNoMoreInteractions(usuarios, audit);
    }

    @Test
    void exactRepeatDoesNotWriteOrAudit() {
        var user = target("exact", true);
        when(usuarios.buscarPorKeycloakId("exact")).thenReturn(Optional.of(user));
        assertThat(useCase.ejecutar(id, "exact")).isEqualTo(id);
        verify(usuarios).bloquearPorId(id);
        verify(usuarios).buscarPorKeycloakId("exact");
        verifyNoMoreInteractions(usuarios);
        verifyNoInteractions(audit);
    }

    @Test
    void missingAndInactiveTargetsFailBeforeSubjectLookup() {
        assertThatThrownBy(() -> useCase.ejecutar(id, "subject")).isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Usuario no encontrado");
        target(null, false);
        assertThatThrownBy(() -> useCase.ejecutar(id, "subject")).isInstanceOf(BusinessRuleException.class)
                .hasMessage("El usuario está inactivo");
        verify(usuarios, times(2)).bloquearPorId(id);
        verifyNoMoreInteractions(usuarios);
        verifyNoInteractions(audit);
    }

    @Test
    void overwriteAndAnotherOwnerFailWithoutConditionalWriteOrAudit() {
        target("old", true);
        assertThatThrownBy(() -> useCase.ejecutar(id, "new")).isInstanceOf(BusinessRuleException.class)
                .hasMessage("La identidad de usuario ya está vinculada");
        target(null, true);
        when(usuarios.buscarPorKeycloakId("owned")).thenReturn(Optional.of(mock(Usuario.class)));
        assertThatThrownBy(() -> useCase.ejecutar(id, "owned")).isInstanceOf(BusinessRuleException.class)
                .hasMessage("La identidad de usuario ya está vinculada");
        verify(usuarios, times(2)).bloquearPorId(id);
        verify(usuarios).buscarPorKeycloakId("new");
        verify(usuarios).buscarPorKeycloakId("owned");
        verifyNoMoreInteractions(usuarios);
        verifyNoInteractions(audit);
    }
}
