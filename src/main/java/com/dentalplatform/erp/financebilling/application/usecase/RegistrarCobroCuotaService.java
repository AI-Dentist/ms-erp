package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.financebilling.domain.repository.*;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.*;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Shared payment core. Cash and future QR callers MUST supply the enclosing transaction.
 * The returned receipt is the financial snapshot (pdfUrlS3 is null). After commit, best-effort PDF generation
 * updates the stored receipt in a separate transaction; cashflow queries expose that URL when available.
 * Artifact failure leaves the committed receipt intact, with a null URL and no automatic retry.
 */
@Service
public class RegistrarCobroCuotaService {
    private static final Logger log = LoggerFactory.getLogger(RegistrarCobroCuotaService.class);
    private final OrdenCobroRepository orders;
    private final MovimientoCajaRepository movements;
    private final ReciboRepository receipts;
    private final ValidarReferenciasFinanzasService referencias;
    private final RegistrarAuditoriaUseCase auditoria;
    private final CompletarPdfReciboService pdf;
    private final Clock clock;

    public RegistrarCobroCuotaService(OrdenCobroRepository orders, MovimientoCajaRepository movements,
                                      ReciboRepository receipts, ValidarReferenciasFinanzasService referencias,
                                      RegistrarAuditoriaUseCase auditoria, CompletarPdfReciboService pdf, Clock clock) {
        this.orders = orders;
        this.movements = movements;
        this.receipts = receipts;
        this.referencias = referencias;
        this.auditoria = auditoria;
        this.pdf = pdf;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Recibo registrar(UUID cuotaId, UUID usuarioCajeroId, MetodoPago metodo, String ciNit, String razonSocial) {
        referencias.cajero(usuarioCajeroId);
        var order = orders.bloquearPorCuotaId(cuotaId)
                .orElseThrow(() -> new ResourceNotFoundException("La cuota no existe"));
        var now = OffsetDateTime.now(clock);
        var paid = order.registrarPagoCuota(cuotaId, now);
        var movement = MovimientoCaja.crear(cuotaId, usuarioCajeroId, TipoMovimientoCaja.INGRESO, "COBRO_CUOTA",
                metodo, paid.montoCuota(), "Cobro cuota " + paid.numeroCuota() + " de orden " + order.getId(), now);
        var receipt = Recibo.emitir(movement, receipts.siguienteNumero(), ciNit, razonSocial);
        orders.guardar(order);
        movements.guardar(movement);
        receipt = receipts.guardar(receipt);
        auditoria.registrar("CUOTA_COBRADA", "cuotas_pago", cuotaId,
                Map.of("reciboId", receipt.id(), "usuarioCajeroId", usuarioCajeroId, "metodo", metodo.name()));
        Recibo committedReceipt = receipt;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try { pdf.completar(committedReceipt); }
                catch (RuntimeException e) {
                    // The legal receipt row already exists. A missing PDF never reverses a committed payment.
                    log.warn("Could not generate/store PDF for receipt {}", committedReceipt.id(), e);
                }
            }
        });
        return receipt;
    }
}
