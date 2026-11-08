package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.application.port.CurrentActorPort;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ObtenerMiUsuarioIdUseCaseTest {
    private final UsuarioRepository usuarios = mock(UsuarioRepository.class);

    private ObtenerMiUsuarioIdUseCase useCase(String subject) {
        CurrentActorPort actor = () -> new CurrentActorPort.Actor(Optional.ofNullable(subject), "matching-username", Optional.empty());
        return new ObtenerMiUsuarioIdUseCase(actor, usuarios);
    }

    @Test
    void absentAndBlankSubjectsFailBeforeAnyRepositoryAccess() {
        for (String subject : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> useCase(subject).ejecutar())
                    .isInstanceOf(ObtenerMiUsuarioIdUseCase.JwtSubjectRequiredException.class)
                    .hasMessage("Se requiere un JWT con subject");
        }
        verifyNoInteractions(usuarios);
    }

    @Test
    void exactSubjectResolvesActiveUserWithoutFallbackOrWrites() {
        var user = mock(Usuario.class);
        var id = UUID.randomUUID();
        when(user.isActivo()).thenReturn(true);
        when(user.getId()).thenReturn(id);
        when(usuarios.buscarPorKeycloakId(" Opaque:SUB ")).thenReturn(Optional.of(user));
        assertThat(useCase(" Opaque:SUB ").ejecutar()).isEqualTo(id);
        verify(usuarios).buscarPorKeycloakId(" Opaque:SUB ");
        verifyNoMoreInteractions(usuarios);
    }

    @Test
    void absentAndInactiveLinksShareGenericNotFoundWithoutWrites() {
        var inactive = mock(Usuario.class);
        when(usuarios.buscarPorKeycloakId("inactive")).thenReturn(Optional.of(inactive));
        for (String subject : new String[]{"absent", "inactive"}) {
            assertThatThrownBy(() -> useCase(subject).ejecutar()).isInstanceOf(ResourceNotFoundException.class)
                    .hasMessage("Identidad de usuario no disponible");
            verify(usuarios).buscarPorKeycloakId(subject);
        }
        verifyNoMoreInteractions(usuarios);
    }
}
