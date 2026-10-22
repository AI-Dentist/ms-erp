package com.dentalplatform.erp.mastercatalog.domain.repository;

import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;

import java.util.List;
import java.util.UUID;

public interface ServicioOdontologicoRepository {

    /** Active services, optionally restricted to one specialty. */
    List<ServicioOdontologico> listarActivos(UUID especialidadId);

    boolean existeCodigo(String codigo);

    ServicioOdontologico guardar(ServicioOdontologico servicio);
}
