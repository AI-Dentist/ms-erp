package com.dentalplatform.erp.financebilling.application.port;

import com.dentalplatform.erp.financebilling.domain.model.Recibo;

public interface N8nNotificationPort {
    /** Best-effort notification after commit. Adapters must not disclose patient identity. */
    void notificar(Recibo receipt);
}
