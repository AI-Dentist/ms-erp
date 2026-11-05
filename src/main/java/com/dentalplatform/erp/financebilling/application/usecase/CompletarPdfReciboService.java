package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.port.*;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Optional artifact generation AFTER the financial commit. Ownership is committed before the upload and the link
 * after it, each in its own transaction, and the upload runs inside neither. A failure after the upload therefore
 * leaves a tracked, non-downloadable object instead of an unreferenced one. Objects are never deleted here.
 */
@Service
public class CompletarPdfReciboService {
    private final ReceiptPdfGeneratorPort pdf;
    private final ReceiptStoragePort storage;
    private final ReciboPdfArtefactoService artefactos;

    public CompletarPdfReciboService(ReceiptPdfGeneratorPort pdf, ReceiptStoragePort storage,
                                     ReciboPdfArtefactoService artefactos) {
        this.pdf = pdf;
        this.storage = storage;
        this.artefactos = artefactos;
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void completar(Recibo receipt) {
        var reserved = artefactos.reservada(receipt.id());
        if (reserved.isPresent()) {
            String reference = storage.referencia(receipt.id());
            if (!reserved.get().equals(reference)) {
                throw new IllegalStateException("La referencia del recibo no coincide con su reserva");
            }
            if (storage.existe(receipt.id())) {
                artefactos.vincular(receipt, reference);
                return;
            }
        }
        byte[] bytes = pdf.generar(receipt);
        String reference = storage.referencia(receipt.id());
        if (reference == null || reference.isBlank()) {
            throw new IllegalStateException("El almacenamiento no declaró la referencia del recibo");
        }
        artefactos.reservar(receipt.id(), reference);
        String stored = storage.guardar(receipt.id(), bytes);
        if (!reference.equals(stored)) {
            // The owner names the reserved key; never link a different, unowned one.
            throw new IllegalStateException("El almacenamiento devolvió una referencia distinta a la reservada");
        }
        artefactos.vincular(receipt, reference);
    }
}
