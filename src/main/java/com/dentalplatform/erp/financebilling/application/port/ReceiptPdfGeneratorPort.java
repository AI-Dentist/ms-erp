package com.dentalplatform.erp.financebilling.application.port;

import com.dentalplatform.erp.financebilling.domain.model.Recibo;

public interface ReceiptPdfGeneratorPort {
    byte[] generar(Recibo recibo);
}
