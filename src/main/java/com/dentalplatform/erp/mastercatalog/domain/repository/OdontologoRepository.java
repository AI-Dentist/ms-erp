package com.dentalplatform.erp.mastercatalog.domain.repository;

import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OdontologoRepository {

    List<Odontologo> listar(boolean soloActivos);

    Optional<Odontologo> buscarPorId(UUID id);

    boolean existeCi(String ci);

    boolean existeMatricula(String matriculaProfesional);

    /** Whether another dentist (not {@code id}) already uses the CI. */
    boolean existeCiDeOtro(String ci, UUID id);

    /** Whether another dentist (not {@code id}) already uses the professional registration. */
    boolean existeMatriculaDeOtro(String matriculaProfesional, UUID id);

    /** Active dentists that have the specialty assigned. */
    long contarActivosPorEspecialidad(UUID especialidadId);

    Odontologo guardar(Odontologo odontologo);
}
