package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.CrearPacienteCommand;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.PacienteCreadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;
import com.dentalplatform.erp.mastercatalog.domain.repository.PacienteRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CrearPacienteUseCaseTest {

    private PacienteRepository pacientes;
    private RegistrarAuditoriaUseCase auditoria;
    private CatalogEventPublisherPort publisher;
    private CrearPacienteUseCase useCase;

    @BeforeEach
    void setUp() {
        pacientes = mock(PacienteRepository.class);
        auditoria = mock(RegistrarAuditoriaUseCase.class);
        publisher = mock(CatalogEventPublisherPort.class);
        useCase = new CrearPacienteUseCase(pacientes, auditoria, publisher,
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
        when(pacientes.guardar(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CrearPacienteCommand command(String ciNit) {
        return new CrearPacienteCommand(ciNit, "Carlos Andrés", "Justiniano", LocalDate.of(1995, 4, 12), "MASCULINO",
                "+59170012345", "carlos@gmail.com", null);
    }

    @Test
    void savesAuditsAndPublishesEventWithPatientData() {
        Paciente creado = useCase.ejecutar(command(" 8945123 SC "));

        assertThat(creado.getCiNit()).isEqualTo("8945123 SC");
        verify(pacientes).existeCiNit("8945123 SC");
        verify(pacientes).guardar(creado);
        verify(auditoria).registrar(eq("PACIENTE_CREADO"), eq("pacientes"), eq(creado.getId()), anyMap());
        ArgumentCaptor<PacienteCreadoEvent> event = ArgumentCaptor.forClass(PacienteCreadoEvent.class);
        verify(publisher).publicar(event.capture());
        assertThat(event.getValue().pacienteId()).isEqualTo(creado.getId());
        assertThat(event.getValue().nombreCompleto()).isEqualTo("Carlos Andrés Justiniano");
        assertThat(event.getValue().fechaNacimiento()).isEqualTo(LocalDate.of(1995, 4, 12));
        assertThat(event.getValue().eventId()).isNotNull();
        assertThat(event.getValue().routingKey()).isEqualTo("catalogo.paciente.creado");
    }

    @Test
    void rejectsDuplicatedCiNitWithoutSavingAuditingOrPublishing() {
        when(pacientes.existeCiNit("8945123 SC")).thenReturn(true);

        assertThatThrownBy(() -> useCase.ejecutar(command("8945123 SC")))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("8945123 SC");
        verify(pacientes, never()).guardar(any());
        verifyNoInteractions(auditoria, publisher);
    }

    @Test
    void invalidDataIsRejectedBeforeAnyPersistence() {
        assertThatThrownBy(() -> useCase.ejecutar(command(" "))).isInstanceOf(BusinessRuleException.class);
        verify(pacientes, never()).guardar(any());
        verifyNoInteractions(auditoria, publisher);
    }
}
