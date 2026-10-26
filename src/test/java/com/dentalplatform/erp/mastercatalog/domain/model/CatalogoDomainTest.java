package com.dentalplatform.erp.mastercatalog.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CatalogoDomainTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-10-21T12:00:00-04:00");

    private static Paciente paciente(String ciNit, String email, LocalDate nacimiento) {
        return Paciente.crear(ciNit, "Carlos Andrés", "Justiniano", nacimiento, "MASCULINO", "+59170012345", email,
                null, NOW);
    }

    private static Odontologo odontologo(String comision, String email) {
        return Odontologo.crear(" 5832147 SC ", "col-od-4512", "Valeria", "Roca", "+59170811223", email,
                new BigDecimal(comision), List.of(), NOW);
    }

    @Test
    void pacienteTrimsCiNitBuildsFullNameAndIsActive() {
        Paciente p = paciente("  8945123 SC ", "carlos@gmail.com", LocalDate.of(1995, 4, 12));

        assertThat(p.getCiNit()).isEqualTo("8945123 SC");
        assertThat(p.nombreCompleto()).isEqualTo("Carlos Andrés Justiniano");
        assertThat(p.isActivo()).isTrue();
        assertThat(p.getId()).isNotNull();
    }

    @Test
    void pacienteAllowsMissingOptionalFields() {
        Paciente p = paciente("123", "  ", null);

        assertThat(p.getEmail()).isNull();
        assertThat(p.getFechaNacimiento()).isNull();
    }

    @Test
    void pacienteRequiresCiNitNamesAndPhone() {
        assertThatThrownBy(() -> paciente(" ", null, null)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("ciNit");
        assertThatThrownBy(() -> Paciente.crear("1", null, "X", null, null, "7", null, null, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("nombres");
        assertThatThrownBy(() -> Paciente.crear("1", "X", "", null, null, "7", null, null, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("apellidos");
        assertThatThrownBy(() -> Paciente.crear("1", "X", "Y", null, null, null, null, null, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("telefonoWhatsapp");
    }

    @Test
    void pacienteRejectsBadEmailAndFutureBirthDate() {
        assertThatThrownBy(() -> paciente("1", "no-es-email", null)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("email");
        assertThatThrownBy(() -> paciente("1", null, LocalDate.of(2026, 10, 22)))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("fecha de nacimiento");
    }

    @Test
    void odontologoUppercasesMatriculaTrimsCiAndNormalizesCommission() {
        Odontologo o = odontologo("35", "v@x.bo");

        assertThat(o.getMatriculaProfesional()).isEqualTo("COL-OD-4512");
        assertThat(o.getCi()).isEqualTo("5832147 SC");
        assertThat(o.getPorcentajeComision()).isEqualByComparingTo("35.00");
        assertThat(o.nombreCompleto()).isEqualTo("Valeria Roca");
    }

    @Test
    void odontologoAcceptsCommissionBoundaries() {
        assertThat(odontologo("0", null).getPorcentajeComision()).isEqualByComparingTo("0");
        assertThat(odontologo("100", null).getPorcentajeComision()).isEqualByComparingTo("100");
    }

    @Test
    void odontologoRejectsCommissionOutOfRangeOrMissing() {
        assertThatThrownBy(() -> odontologo("-0.01", null)).isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("entre 0 y 100");
        assertThatThrownBy(() -> odontologo("100.01", null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Odontologo.crear("1", "M", "A", "B", "7", null, null, List.of(), NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("porcentajeComision");
    }

    @Test
    void odontologoRejectsInvalidEmailAndMissingMatricula() {
        assertThatThrownBy(() -> odontologo("10", "sin-arroba")).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Odontologo.crear("1", " ", "A", "B", "7", null, BigDecimal.TEN, List.of(), NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("matriculaProfesional");
    }

    @Test
    void especialidadAndConsultorioCodesAreTrimmedAndUppercased() {
        assertThat(Especialidad.crear(" ort ", "Ortodoncia", null).getCodigo()).isEqualTo("ORT");
        Consultorio c = Consultorio.crear(" cons-05 ", "Consultorio 5", " Primer piso ", NOW);
        assertThat(c.getCodigo()).isEqualTo("CONS-05");
        assertThat(c.getUbicacion()).isEqualTo("Primer piso");
        assertThat(c.getEstado()).isEqualTo("DISPONIBLE");
    }

    @Test
    void especialidadAndConsultorioRequireCodeAndName() {
        assertThatThrownBy(() -> Especialidad.crear(null, "X", null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Especialidad.crear("X", " ", null)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Consultorio.crear("", "X", null, NOW)).isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> Consultorio.crear("C", null, null, NOW)).isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void servicioKeepsPriceAsMoneyWithTwoDecimalsAndAllowsZero() {
        ServicioOdontologico s = ServicioOdontologico.crear(null, " odg-cons ", "Consulta", null,
                new BigDecimal("100"), NOW);

        assertThat(s.getCodigo()).isEqualTo("ODG-CONS");
        assertThat(s.getPrecioBase().amount()).isEqualByComparingTo("100.00");
        assertThat(s.getEspecialidad()).isNull();
        assertThat(ServicioOdontologico.crear(null, "GRATIS", "Control", null, BigDecimal.ZERO, NOW)
                .getPrecioBase().isZero()).isTrue();
    }

    @Test
    void servicioRejectsNegativeOrMissingPrice() {
        assertThatThrownBy(() -> ServicioOdontologico.crear(null, "X", "X", null, new BigDecimal("-1"), NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("negativo");
        assertThatThrownBy(() -> ServicioOdontologico.crear(null, "X", "X", null, null, NOW))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("precioBaseBs");
    }
}
