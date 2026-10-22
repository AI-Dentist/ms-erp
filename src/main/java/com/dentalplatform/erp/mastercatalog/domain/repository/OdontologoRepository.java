package com.dentalplatform.erp.mastercatalog.domain.repository;

import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;

import java.util.List;

public interface OdontologoRepository {

    List<Odontologo> listar(boolean soloActivos);

    boolean existeCi(String ci);

    boolean existeMatricula(String matriculaProfesional);

    Odontologo guardar(Odontologo odontologo);
}
