package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.application.port.ReceiptArtifactOwnershipPort;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import com.dentalplatform.erp.financebilling.domain.repository.ReciboRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Short independent transactions; never hold a receipt row lock while accessing storage. */
@Service
public class ReciboPdfArtefactoService {
    public static final String RESERVADO = "RECIBO_PDF_RESERVADO";
    private final RegistrarAuditoriaUseCase auditoria;
    private final ReciboRepository receipts;
    private final ReceiptArtifactOwnershipPort ownership;

    public ReciboPdfArtefactoService(RegistrarAuditoriaUseCase auditoria, ReciboRepository receipts,
                                     ReceiptArtifactOwnershipPort ownership) {
        this.auditoria = auditoria;
        this.receipts = receipts;
        this.ownership = ownership;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<String> reservada(UUID reciboId) {
        return ownership.referenciaReservada(reciboId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reservar(UUID reciboId, String referencia) {
        ownership.bloquearRecibo(reciboId);
        var existing = ownership.referenciaReservada(reciboId);
        if (existing.isPresent()) {
            if (!existing.get().equals(referencia)) throw conflict();
            return;
        }
        auditoria.registrar(RESERVADO, "recibos", reciboId, Map.of("referencia", referencia));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void vincular(Recibo receipt, String referencia) {
        vincularBajoBloqueo(receipt, referencia);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void reparar(Recibo receipt, String referencia) {
        if (vincularBajoBloqueo(receipt, referencia)) {
            auditoria.registrar("RECIBO_PDF_REPARADO", "recibos", receipt.id(), Map.of("estado", "VINCULADO"));
        }
    }

    private boolean vincularBajoBloqueo(Recibo receipt, String referencia) {
        ownership.bloquearRecibo(receipt.id());
        if (ownership.referenciaReservada(receipt.id()).filter(referencia::equals).isEmpty()) throw conflict();
        Recibo actual = receipts.buscarPorMovimiento(receipt.movimientoCajaId())
                .filter(r -> receipt.id().equals(r.id()))
                .orElseThrow(() -> new IllegalStateException("Recibo no disponible para el artefacto"));
        if (actual.pdfUrlS3() != null) {
            if (!referencia.equals(actual.pdfUrlS3())) throw conflict();
            return false;
        }
        receipts.guardar(actual.conPdf(referencia));
        return true;
    }

    private static IllegalStateException conflict() {
        return new IllegalStateException("La referencia del artefacto no coincide con su reserva");
    }
}
