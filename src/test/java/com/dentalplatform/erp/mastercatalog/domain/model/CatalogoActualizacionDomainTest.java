package com.dentalplatform.erp.mastercatalog.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogoActualizacionDomainTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T12:00:00-04:00");

    private static Paciente paciente() {
        return Paciente.crear("8945123 SC", "Carlos", "Justiniano", LocalDate.of(1995, 4, 12), "MASCULINO",
                "+59170012345", "c@gmail.com", "Av. Banzer", NOW);
    }

    private static Especialidad especialidad() {
        return Especialidad.crear("ORTO", "Ortodoncia", "Brackets");
    }

    // ---- Paciente

    @Test
    void pacienteActualizarKeepsIdentityStateAndReplacesEveryField() {
        Paciente original = paciente();

        Paciente editado = original.actualizar(" 123 ", " Ana ", "Rojas", null, null, "+59171111111", " ANA@x.com ",
                null, NOW);

        assertThat(editado.getId()).isEqualTo(original.getId());
        assertThat(editado.getCreatedAt()).isEqualTo(original.getCreatedAt());
        assertThat(editado.isActivo()).isTrue();
        assertThat(editado.getCiNit()).isEqualTo("123");
        assertThat(editado.nombreCompleto()).isEqualTo("Ana Rojas");
        assertThat(editado.getFechaNacimiento()).isNull();
        assertThat(editado.getDireccion()).isNull();
        assertThat(editado.getEmail()).isEqualTo("ANA@x.com");
        assertThat(original.getCiNit()).isEqualTo("8945123 SC");
    }

    @Test
    void pacienteActualizarRejectsFutureBirthDateAndMissingRequiredFields() {
        Paciente p = paciente();

        assertThatThrownBy(() -> p.actualizar("1", "A", "B", LocalDate.of(2030, 1, 1), null, "+591", null, null, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessage("La fecha de nacimiento no puede ser futura");
        assertThatThrownBy(() -> p.actualizar(" ", "A", "B", null, null, "+591", null, null, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessage("El campo ciNit es obligatorio");
        assertThatThrownBy(() -> p.actualizar("1", "A", "B", null, null, "+591", "no-email", null, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("El email no es válido");
    }

    @Test
    void pacienteDesactivarIsBlockedByOpenOrdersAndLeavesTheOriginalActive() {
        Paciente p = paciente();

        assertThatThrownBy(() -> p.desactivar(2)).isInstanceOf(BusinessRuleException.class)
                .hasMessage("No se puede desactivar el paciente: tiene 2 orden(es) de cobro PENDIENTE o PARCIAL");
        assertThat(p.isActivo()).isTrue();
        assertThat(p.desactivar(0).isActivo()).isFalse();
        assertThat(p.isActivo()).isTrue();
    }

    // ---- Especialidad

    @Test
    void especialidadActualizarNormalizesCodeAndKeepsActiveFlag() {
        Especialidad e = especialidad();

        Especialidad editada = e.actualizar(" endo ", " Endodoncia ", "  ");

        assertThat(editada.getId()).isEqualTo(e.getId());
        assertThat(editada.getCodigo()).isEqualTo("ENDO");
        assertThat(editada.getNombre()).isEqualTo("Endodoncia");
        assertThat(editada.getDescripcion()).isNull();
        assertThat(editada.isActivo()).isTrue();
    }

    @Test
    void especialidadDesactivarIsBlockedWhileActiveServicesOrDentistsReferenceIt() {
        Especialidad e = especialidad();

        assertThatThrownBy(() -> e.desactivar(1, 0)).isInstanceOf(BusinessRuleException.class)
                .hasMessage("No se puede desactivar la especialidad: la usan 1 servicio(s) activo(s)");
        assertThatThrownBy(() -> e.desactivar(0, 3)).isInstanceOf(BusinessRuleException.class)
                .hasMessage("No se puede desactivar la especialidad: la usan 3 odontólogo(s) activo(s)");
        assertThatThrownBy(() -> e.desactivar(2, 3)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("2 servicio(s) activo(s)").hasMessageContaining("3 odontólogo(s) activo(s)");
        assertThat(e.desactivar(0, 0).isActivo()).isFalse();
        assertThat(e.isActivo()).isTrue();
    }

    // ---- Odontologo

    @Test
    void odontologoActualizarReplacesSpecialtiesAndValidatesCommission() {
        Odontologo o = Odontologo.crear("1", "mat-1", "Valeria", "Roca", "+591", null, new BigDecimal("30"),
                List.of(especialidad()), NOW);
        Especialidad otra = Especialidad.crear("ENDO", "Endodoncia", null);

        Odontologo editado = o.actualizar("2", "mat-2", "Vale", "Roca", "+592", "v@x.com", new BigDecimal("40.555"),
                List.of(otra));

        assertThat(editado.getId()).isEqualTo(o.getId());
        assertThat(editado.getMatriculaProfesional()).isEqualTo("MAT-2");
        assertThat(editado.getPorcentajeComision()).isEqualByComparingTo("40.56");
        assertThat(editado.getEspecialidades()).containsExactly(otra);
        assertThat(editado.isActivo()).isTrue();
        assertThat(editado.getCreatedAt()).isEqualTo(o.getCreatedAt());
        assertThatThrownBy(() -> o.actualizar("2", "m", "V", "R", "+592", null, new BigDecimal("101"), List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("El porcentaje de comisión debe estar entre 0 y 100");
        assertThat(o.desactivar().isActivo()).isFalse();
    }

    // ---- Consultorio

    @Test
    void consultorioActualizarValidatesEstadoAgainstTheExplicitSet() {
        Consultorio c = Consultorio.crear("cons-01", "Consultorio 1", null, NOW);

        assertThat(c.actualizar("cons-09", "Nuevo", "Piso 2", " mantenimiento ").getEstado())
                .isEqualTo("MANTENIMIENTO");
        assertThat(c.actualizar("cons-09", "Nuevo", "Piso 2", null).getEstado()).isEqualTo("DISPONIBLE");
        assertThat(Consultorio.ESTADOS).containsExactly("DISPONIBLE", "MANTENIMIENTO", "INACTIVO");
        assertThatThrownBy(() -> c.actualizar("cons-09", "Nuevo", null, "OCUPADO"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Estado de consultorio no válido: OCUPADO. Valores permitidos: DISPONIBLE, MANTENIMIENTO, INACTIVO");
    }

    @Test
    void consultorioDesactivarMovesToInactivoAndIsReportedInactive() {
        Consultorio c = Consultorio.crear("cons-01", "Consultorio 1", null, NOW);

        assertThat(c.isActivo()).isTrue();
        Consultorio inactivo = c.desactivar();
        assertThat(inactivo.getEstado()).isEqualTo("INACTIVO");
        assertThat(inactivo.isActivo()).isFalse();
        assertThat(inactivo.getId()).isEqualTo(c.getId());
        assertThat(c.getEstado()).isEqualTo("DISPONIBLE");
    }

    // ---- Servicio

    @Test
    void servicioActualizarChangesPriceWithoutTouchingIdentityAndRejectsNegative() {
        ServicioOdontologico s = ServicioOdontologico.crear(especialidad(), "srv-1", "Limpieza", null,
                new BigDecimal("100"), NOW);

        ServicioOdontologico editado = s.actualizar(null, "srv-2", "Limpieza profunda", "desc",
                new BigDecimal("150.5"));

        assertThat(editado.getId()).isEqualTo(s.getId());
        assertThat(editado.getEspecialidad()).isNull();
        assertThat(editado.getCodigo()).isEqualTo("SRV-2");
        assertThat(editado.getPrecioBase().amount()).isEqualByComparingTo("150.50");
        assertThat(s.getPrecioBase().amount()).isEqualByComparingTo("100");
        assertThatThrownBy(() -> s.actualizar(null, "x", "y", null, new BigDecimal("-1")))
                .isInstanceOf(BusinessRuleException.class).hasMessage("El precio base no puede ser negativo");
        assertThat(s.desactivar().isActivo()).isFalse();
    }
}
