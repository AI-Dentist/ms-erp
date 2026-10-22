package com.dentalplatform.erp.financebilling.domain.repository;

import com.dentalplatform.erp.financebilling.domain.model.MovimientoCaja;
import java.time.OffsetDateTime;
import java.util.List;

public interface MovimientoCajaRepository {
    MovimientoCaja guardar(MovimientoCaja movimiento);
    List<MovimientoCaja> listar(OffsetDateTime inicio, OffsetDateTime fin);
}
