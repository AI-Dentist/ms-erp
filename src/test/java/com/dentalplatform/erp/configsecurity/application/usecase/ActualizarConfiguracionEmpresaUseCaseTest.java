package com.dentalplatform.erp.configsecurity.application.usecase;

import com.dentalplatform.erp.configsecurity.application.command.ActualizarEmpresaCommand;
import com.dentalplatform.erp.configsecurity.domain.model.EmpresaConfig;
import com.dentalplatform.erp.configsecurity.domain.repository.EmpresaConfigRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
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
import static org.mockito.Mockito.when;

class ActualizarConfiguracionEmpresaUseCaseTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T08:00:00-04:00");

    private EmpresaConfigRepository repository;
    private RegistrarAuditoriaUseCase auditoria;
    private ActualizarConfiguracionEmpresaUseCase useCase;

    @BeforeEach
    void setUp() {
        repository = mock(EmpresaConfigRepository.class);
        auditoria = mock(RegistrarAuditoriaUseCase.class);
        useCase = new ActualizarConfiguracionEmpresaUseCase(repository, auditoria,
                Clock.fixed(Instant.parse("2026-10-21T12:00:00Z"), ZoneOffset.UTC));
        when(repository.guardar(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void updatesExistingConfigurationKeepingItsId() {
        UUID id = UUID.randomUUID();
        when(repository.obtener()).thenReturn(Optional.of(new EmpresaConfig(id, "Old", "Old SRL", "123456", "Dir",
                "+591", null, null, "Bs", NOW)));

        EmpresaConfig result = useCase.ejecutar(validCommand());

        assertThat(result.getId()).isEqualTo(id);
        assertThat(result.getNombreComercial()).isEqualTo("Clínica Dental Sonrisa Cruceña");
        assertThat(result.getNit()).isEqualTo("1023456029");
        assertThat(result.getMonedaSimbolo()).isEqualTo("Bs");
        verify(auditoria).registrar(eq("EMPRESA_CONFIG_ACTUALIZADA"), eq("empresa_config"), eq(id), anyMap());
    }

    @Test
    void createsConfigurationWhenNoneExists() {
        when(repository.obtener()).thenReturn(Optional.empty());

        EmpresaConfig result = useCase.ejecutar(validCommand());

        assertThat(result.getId()).isNotNull();
        assertThat(result.getRazonSocial()).isEqualTo("Sonrisa Cruceña S.R.L.");
    }

    @Test
    void rejectsNonNumericNit() {
        when(repository.obtener()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.ejecutar(new ActualizarEmpresaCommand("A", "B", "NIT-12", "Dir", "+591", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("NIT");
        verify(repository, never()).guardar(any());
    }

    @Test
    void rejectsBlankRequiredFields() {
        when(repository.obtener()).thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.ejecutar(new ActualizarEmpresaCommand(" ", "B", "123456", "Dir", "+591", null)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("nombreComercial");
        verify(repository, never()).guardar(any());
    }

    private static ActualizarEmpresaCommand validCommand() {
        return new ActualizarEmpresaCommand("Clínica Dental Sonrisa Cruceña", "Sonrisa Cruceña S.R.L.", "1023456029",
                "Av. San Martín #155, Santa Cruz de la Sierra", "+591 3 3412233", "contacto@sonrisacrucena.bo");
    }
}
