package com.dentalplatform.erp.financebilling.domain.repository;

import com.dentalplatform.erp.financebilling.domain.model.TransaccionQr;
import java.util.Optional;

public interface TransaccionQrRepository {
    TransaccionQr guardar(TransaccionQr transaction);
    Optional<TransaccionQr> buscar(String reference);
    /** Only call after acquiring the owning order lock. */
    Optional<TransaccionQr> bloquear(String reference);
}
