package com.dentalplatform.erp.financebilling.domain.repository;

import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import java.util.Optional;
import java.util.UUID;

public interface ReciboRepository {
    long siguienteNumero();
    Recibo guardar(Recibo recibo);
    Optional<Recibo> buscarPorMovimiento(UUID movimientoId);
}
