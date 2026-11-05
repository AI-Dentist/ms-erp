package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.port.ReceiptStoragePort;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** A caller-authorized download retry reuses an existing object; never regenerates or uploads it. */
@Service
public class RepararPdfReciboUseCase {
    private final ReciboPdfArtefactoService artefactos;
    private final ReceiptStoragePort storage;

    public RepararPdfReciboUseCase(ReciboPdfArtefactoService artefactos, ReceiptStoragePort storage) {
        this.artefactos = artefactos;
        this.storage = storage;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public String ejecutar(Recibo receipt) {
        var reserved = artefactos.reservada(receipt.id());
        if (reserved.isEmpty()) return null;
        String reference = reserved.get();
        if (!reference.equals(storage.referencia(receipt.id()))) {
            throw new IllegalStateException("La referencia reservada no pertenece al almacenamiento del recibo");
        }
        if (!storage.existe(receipt.id())) return null;
        artefactos.reparar(receipt, reference);
        return reference;
    }
}
