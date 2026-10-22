package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.CrearOdontologoCommand;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.OdontologoCreadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.OdontologoRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CrearOdontologoUseCaseTest {

    private static final Especialidad ORTODONCIA = new Especialidad(UUID.randomUUID(), "ORT", "Ortodoncia", null, true);
    private static final Especialidad ESTETICA = new Especialidad(UUID.randomUUID(), "EST", "Estética Dental", null, true);

    private OdontologoRepository odontologos;
    private EspecialidadRepository especialidades;
    private RegistrarAuditoriaUseCase auditoria;
    private CatalogEventPublisherPort publisher;
    private CrearOdontologoUseCase useCase;

    @BeforeEach
    void setUp() {
        odontologos = mock(OdontologoRepository.class);
        especialidades = mock(EspecialidadRepository.class);
        auditoria = mock(RegistrarAuditoriaUseCase.class);
        publisher = mock(CatalogEventPublisherPort.class);
        useCase = new CrearOdontologoUseCase(odontologos, especialidades, auditoria, publisher,
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
        when(odontologos.guardar(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private static CrearOdontologoCommand command(List<UUID> ids) {
        return new CrearOdontologoCommand("5832147 SC", "col-od-4512", "Valeria", "Roca", "+59170811223", null,
                new BigDecimal("35"), ids);
    }

    @Test
    void savesAuditsAndPublishesEventWithSpecialtyNames() {
        List<UUID> ids = List.of(ORTODONCIA.getId(), ESTETICA.getId());
        when(especialidades.buscarPorIds(anyCollection())).thenReturn(List.of(ORTODONCIA, ESTETICA));

        Odontologo creado = useCase.ejecutar(command(ids));

        assertThat(creado.getMatriculaProfesional()).isEqualTo("COL-OD-4512");
        assertThat(creado.getEspecialidades()).containsExactly(ORTODONCIA, ESTETICA);
        verify(auditoria).registrar(eq("ODONTOLOGO_CREADO"), eq("odontologos"), eq(creado.getId()), anyMap());
        ArgumentCaptor<OdontologoCreadoEvent> event = ArgumentCaptor.forClass(OdontologoCreadoEvent.class);
        verify(publisher).publicar(event.capture());
        assertThat(event.getValue().especialidades()).containsExactly("Ortodoncia", "Estética Dental");
        assertThat(event.getValue().matriculaProfesional()).isEqualTo("COL-OD-4512");
        assertThat(event.getValue().routingKey()).isEqualTo("catalogo.odontologo.creado");
    }

    @Test
    void rejectsUnknownSpecialtyWithoutPersistingOrPublishing() {
        UUID unknown = UUID.randomUUID();
        when(especialidades.buscarPorIds(anyCollection())).thenReturn(List.of(ORTODONCIA));

        assertThatThrownBy(() -> useCase.ejecutar(command(List.of(ORTODONCIA.getId(), unknown))))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining(unknown.toString());
        verify(odontologos, never()).guardar(any());
        verifyNoInteractions(auditoria, publisher);
    }

    @Test
    void rejectsDuplicatedCiAndMatricula() {
        when(especialidades.buscarPorIds(anyCollection())).thenReturn(List.of());
        when(odontologos.existeCi("5832147 SC")).thenReturn(true);
        assertThatThrownBy(() -> useCase.ejecutar(command(List.of())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("5832147 SC");

        when(odontologos.existeCi("5832147 SC")).thenReturn(false);
        when(odontologos.existeMatricula("COL-OD-4512")).thenReturn(true);
        assertThatThrownBy(() -> useCase.ejecutar(command(List.of())))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("COL-OD-4512");
        verify(odontologos, never()).guardar(any());
        verifyNoInteractions(auditoria, publisher);
    }

    @Test
    void ignoresRepeatedSpecialtyIds() {
        when(especialidades.buscarPorIds(anyCollection())).thenReturn(List.of(ORTODONCIA));

        Odontologo creado = useCase.ejecutar(command(List.of(ORTODONCIA.getId(), ORTODONCIA.getId())));

        assertThat(creado.getEspecialidades()).containsExactly(ORTODONCIA);
    }
}
