package com.dentalplatform.erp.configsecurity.domain.repository;

import com.dentalplatform.erp.configsecurity.domain.model.Rol;

import java.util.List;
import java.util.Optional;

public interface RolRepository {

    List<Rol> listar();

    Optional<Rol> buscarPorCodigo(String codigo);
}
