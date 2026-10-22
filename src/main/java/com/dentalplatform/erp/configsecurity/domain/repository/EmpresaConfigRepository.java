package com.dentalplatform.erp.configsecurity.domain.repository;

import com.dentalplatform.erp.configsecurity.domain.model.EmpresaConfig;

import java.util.Optional;

public interface EmpresaConfigRepository {

    Optional<EmpresaConfig> obtener();

    EmpresaConfig guardar(EmpresaConfig config);
}
