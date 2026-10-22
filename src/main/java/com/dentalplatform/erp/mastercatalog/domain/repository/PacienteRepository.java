package com.dentalplatform.erp.mastercatalog.domain.repository;

import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PacienteRepository {

    /** Active patients whose CI/NIT, names or surnames contain the text (case-insensitive); all when blank. */
    List<Paciente> buscarActivos(String busqueda);

    Optional<Paciente> buscarPorId(UUID id);

    boolean existeCiNit(String ciNit);

    Paciente guardar(Paciente paciente);
}
