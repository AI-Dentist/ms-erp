package com.dentalplatform.erp.mastercatalog.domain.repository;

import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;

import java.util.List;

public interface ConsultorioRepository {

    List<Consultorio> listar();

    boolean existeCodigo(String codigo);

    Consultorio guardar(Consultorio consultorio);
}
