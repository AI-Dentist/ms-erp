package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

/** No production wire format, signature verification or provider acceptance proof has been supplied. */
public class UnsupportedPagoFacilIntegrationException extends BusinessRuleException {
    public UnsupportedPagoFacilIntegrationException() {
        super("Integración PagoFácil de producción no soportada: faltan protocolo y verificación de firma del proveedor");
    }
}
