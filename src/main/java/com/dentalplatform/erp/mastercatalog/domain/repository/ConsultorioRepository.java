package com.dentalplatform.erp.mastercatalog.domain.repository;

import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConsultorioRepository {

    List<Consultorio> listar();

    Optional<Consultorio> buscarPorId(UUID id);

    boolean existeCodigo(String codigo);

    /** Whether another office (not {@code id}) already uses the code. */
    boolean existeCodigoDeOtro(String codigo, UUID id);

    Consultorio guardar(Consultorio consultorio);
}
