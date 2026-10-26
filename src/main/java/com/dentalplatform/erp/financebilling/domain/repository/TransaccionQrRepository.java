package com.dentalplatform.erp.financebilling.domain.repository;

import com.dentalplatform.erp.financebilling.domain.model.TransaccionQr;
import java.util.Optional;

public interface TransaccionQrRepository {
    TransaccionQr guardar(TransaccionQr transaction);
    Optional<TransaccionQr> buscar(String reference);
    /** The newest generated (unconfirmed) and not yet expired QR of an installment, if any. */
    Optional<TransaccionQr> buscarVigentePorCuota(java.util.UUID cuotaId, java.time.OffsetDateTime ahora);
    /** Only call after acquiring the owning order lock. */
    Optional<TransaccionQr> bloquear(String reference);
}
