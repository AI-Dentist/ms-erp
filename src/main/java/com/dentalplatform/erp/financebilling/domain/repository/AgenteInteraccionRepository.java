package com.dentalplatform.erp.financebilling.domain.repository;

import com.dentalplatform.erp.financebilling.domain.model.AgenteInteraccion;
import java.util.List;

public interface AgenteInteraccionRepository {
    AgenteInteraccion guardar(AgenteInteraccion interaccion);

    /** Newest first. A null {@code solicitante} lists every requester's interactions. */
    List<AgenteInteraccion> listarRecientes(String solicitante, int limite);
}
