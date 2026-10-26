package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.mastercatalog.application.command.CrearConsultorioCommand;
import com.dentalplatform.erp.mastercatalog.application.command.CrearEspecialidadCommand;
import com.dentalplatform.erp.mastercatalog.application.command.CrearServicioOdontologicoCommand;
import com.dentalplatform.erp.mastercatalog.application.port.CatalogEventPublisherPort;
import com.dentalplatform.erp.mastercatalog.domain.event.ConsultorioCreadoEvent;
import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;
import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;
import com.dentalplatform.erp.mastercatalog.domain.repository.ConsultorioRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.ServicioOdontologicoRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

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

class CrearCatalogoSimpleUseCasesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC);

    private final RegistrarAuditoriaUseCase auditoria = mock(RegistrarAuditoriaUseCase.class);
    private final CatalogEventPublisherPort publisher = mock(CatalogEventPublisherPort.class);
    private final EspecialidadRepository especialidades = mock(EspecialidadRepository.class);
    private final ConsultorioRepository consultorios = mock(ConsultorioRepository.class);
    private final ServicioOdontologicoRepository servicios = mock(ServicioOdontologicoRepository.class);

    @BeforeEach
    void setUp() {
        when(especialidades.guardar(any())).thenAnswer(inv -> inv.getArgument(0));
        when(consultorios.guardar(any())).thenAnswer(inv -> inv.getArgument(0));
        when(servicios.guardar(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void createsSpecialtyAndAuditsWithoutPublishing() {
        Especialidad creada = new CrearEspecialidadUseCase(especialidades, auditoria)
                .ejecutar(new CrearEspecialidadCommand(" pdo ", "Odontopediatría", null));

        assertThat(creada.getCodigo()).isEqualTo("PDO");
        verify(auditoria).registrar(eq("ESPECIALIDAD_CREADA"), eq("especialidades"), eq(creada.getId()), anyMap());
        verifyNoInteractions(publisher);
    }

    @Test
    void rejectsDuplicatedSpecialtyCode() {
        when(especialidades.existeCodigo("PDO")).thenReturn(true);

        assertThatThrownBy(() -> new CrearEspecialidadUseCase(especialidades, auditoria)
                .ejecutar(new CrearEspecialidadCommand("pdo", "Odontopediatría", null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("PDO");
        verify(especialidades, never()).guardar(any());
        verifyNoInteractions(auditoria);
    }

    @Test
    void createsAvailableOfficeAndPublishesEvent() {
        CrearConsultorioUseCase useCase = new CrearConsultorioUseCase(consultorios, auditoria, publisher, CLOCK);

        Consultorio creado = useCase.ejecutar(new CrearConsultorioCommand("cons-05", "Consultorio 5", null));

        assertThat(creado.getEstado()).isEqualTo("DISPONIBLE");
        verify(auditoria).registrar(eq("CONSULTORIO_CREADO"), eq("consultorios"), eq(creado.getId()), anyMap());
        ArgumentCaptor<ConsultorioCreadoEvent> event = ArgumentCaptor.forClass(ConsultorioCreadoEvent.class);
        verify(publisher).publicar(event.capture());
        assertThat(event.getValue().codigo()).isEqualTo("CONS-05");
        assertThat(event.getValue().estado()).isEqualTo("DISPONIBLE");
        assertThat(event.getValue().routingKey()).isEqualTo("catalogo.consultorio.creado");
    }

    @Test
    void rejectsDuplicatedOfficeCodeWithoutPublishing() {
        when(consultorios.existeCodigo("CONS-05")).thenReturn(true);

        assertThatThrownBy(() -> new CrearConsultorioUseCase(consultorios, auditoria, publisher, CLOCK)
                .ejecutar(new CrearConsultorioCommand("CONS-05", "Consultorio 5", null)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("CONS-05");
        verify(consultorios, never()).guardar(any());
        verifyNoInteractions(auditoria, publisher);
    }

    @Test
    void createsServiceLinkedToSpecialtyAndNeverPublishes() {
        Especialidad ort = new Especialidad(UUID.randomUUID(), "ORT", "Ortodoncia", null, true);
        when(especialidades.buscarPorId(ort.getId())).thenReturn(Optional.of(ort));

        ServicioOdontologico creado = new CrearServicioOdontologicoUseCase(servicios, especialidades, auditoria, CLOCK)
                .ejecutar(new CrearServicioOdontologicoCommand(ort.getId(), "ort-xyz", "Retenedor", null,
                        new BigDecimal("350")));

        assertThat(creado.getEspecialidad()).isEqualTo(ort);
        assertThat(creado.getPrecioBase().amount()).isEqualByComparingTo("350.00");
        verify(auditoria).registrar(eq("SERVICIO_ODONTOLOGICO_CREADO"), eq("servicios_odontologicos"),
                eq(creado.getId()), anyMap());
        verifyNoInteractions(publisher);
    }

    @Test
    void serviceWithoutSpecialtyIsAllowedButUnknownSpecialtyAndDuplicatedCodeAreRejected() {
        CrearServicioOdontologicoUseCase useCase =
                new CrearServicioOdontologicoUseCase(servicios, especialidades, auditoria, CLOCK);
        assertThat(useCase.ejecutar(new CrearServicioOdontologicoCommand(null, "GEN-1", "General", null,
                BigDecimal.TEN)).getEspecialidad()).isNull();

        UUID unknown = UUID.randomUUID();
        when(especialidades.buscarPorId(unknown)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> useCase.ejecutar(new CrearServicioOdontologicoCommand(unknown, "GEN-2", "X", null,
                BigDecimal.TEN))).isInstanceOf(BusinessRuleException.class).hasMessageContaining(unknown.toString());

        when(servicios.existeCodigo("GEN-3")).thenReturn(true);
        assertThatThrownBy(() -> useCase.ejecutar(new CrearServicioOdontologicoCommand(null, "gen-3", "X", null,
                BigDecimal.TEN))).isInstanceOf(BusinessRuleException.class).hasMessageContaining("GEN-3");
    }
}
