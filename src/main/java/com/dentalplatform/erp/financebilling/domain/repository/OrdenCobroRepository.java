package com.dentalplatform.erp.financebilling.domain.repository;

import com.dentalplatform.erp.financebilling.domain.model.*;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrdenCobroRepository {
    OrdenCobro guardar(OrdenCobro orden);
    Optional<OrdenCobro> buscarPorId(UUID id);
    Optional<OrdenCobro> bloquearPorId(UUID id);
    Optional<OrdenCobro> bloquearPorCuotaId(UUID cuotaId);
    boolean existeConsulta(UUID consultaId);
    /** Serializes deliveries of the same consultation before checking existence. */
    void bloquearConsulta(UUID consultaId);
    List<OrdenCobro> listar(EstadoOrdenCobro estado, UUID pacienteId);
    List<CuotaPago> cuotasPendientes(UUID pacienteId);
}
