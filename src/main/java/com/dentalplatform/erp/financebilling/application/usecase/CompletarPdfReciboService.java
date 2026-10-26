package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.port.*;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import com.dentalplatform.erp.financebilling.domain.repository.ReciboRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Optional artifact generation, always in a separate transaction AFTER the financial commit. */
@Service
public class CompletarPdfReciboService {
    private final ReceiptPdfGeneratorPort pdf;
    private final ReceiptStoragePort storage;
    private final ReciboRepository receipts;

    public CompletarPdfReciboService(ReceiptPdfGeneratorPort pdf, ReceiptStoragePort storage, ReciboRepository receipts) {
        this.pdf = pdf;
        this.storage = storage;
        this.receipts = receipts;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void completar(Recibo receipt) {
        byte[] bytes = pdf.generar(receipt);
        String url = storage.guardar(receipt.id(), bytes);
        receipts.guardar(receipt.conPdf(url));
    }
}
