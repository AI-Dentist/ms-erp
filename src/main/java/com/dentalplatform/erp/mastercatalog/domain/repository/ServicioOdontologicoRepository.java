package com.dentalplatform.erp.mastercatalog.domain.repository;

import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ServicioOdontologicoRepository {

    /** Active services, optionally restricted to one specialty. */
    List<ServicioOdontologico> listarActivos(UUID especialidadId);

    Optional<ServicioOdontologico> buscarPorId(UUID id);

    boolean existeCodigo(String codigo);

    /** Whether another service (not {@code id}) already uses the code. */
    boolean existeCodigoDeOtro(String codigo, UUID id);

    /** Active services assigned to the specialty. */
    long contarActivosPorEspecialidad(UUID especialidadId);

    ServicioOdontologico guardar(ServicioOdontologico servicio);
}
