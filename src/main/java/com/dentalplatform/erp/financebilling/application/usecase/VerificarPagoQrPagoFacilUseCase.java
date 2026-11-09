package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.port.PagoFacilGatewayPort;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.financebilling.domain.repository.*;
import com.dentalplatform.erp.shared.domain.exception.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;

@Service
public class VerificarPagoQrPagoFacilUseCase {
    private final TransaccionQrRepository transactions;
    private final OrdenCobroRepository orders;
    private final ReciboRepository receipts;
    private final RegistrarCobroCuotaService payment;
    private final PagoFacilGatewayPort gateway;
    private final Clock clock;

    public VerificarPagoQrPagoFacilUseCase(TransaccionQrRepository transactions, OrdenCobroRepository orders,
                                          ReciboRepository receipts, RegistrarCobroCuotaService payment,
                                          PagoFacilGatewayPort gateway, Clock clock) {
        this.transactions = transactions;
        this.orders = orders;
        this.receipts = receipts;
        this.payment = payment;
        this.gateway = gateway;
        this.clock = clock;
    }

    @Transactional
    public Recibo ejecutar(String reference) {
        if (reference == null || reference.isBlank()) {
            throw new BusinessRuleException("Referencia PagoFácil requerida");
        }
        var initial = transactions.buscar(reference)
                .orElseThrow(() -> new ResourceNotFoundException("La transacción QR no existe"));
        // Global lock order: ORDER then QR
        orders.bloquearPorCuotaId(initial.cuotaId())
                .orElseThrow(() -> new ResourceNotFoundException("La cuota no existe"));
        var transaction = transactions.bloquear(reference).orElseThrow();

        if ("CONFIRMADO".equals(transaction.estado())) {
            return receipts.buscarPorCuota(transaction.cuotaId()).orElseThrow();
        }

        String refToQuery = (transaction.pagofacilTransactionId() != null && !transaction.pagofacilTransactionId().isBlank())
                ? transaction.pagofacilTransactionId() : reference;
        var consulta = gateway.consultarTransaccion(refToQuery);
        if (!consulta.pagado()) {
            throw new BusinessRuleException("El pago no ha sido completado en PagoFácil");
        }
        if (consulta.currencyId() != null && consulta.currencyId() != 2) {
            throw new BusinessRuleException("Moneda de transacción inválida en PagoFácil");
        }

        BigDecimal scaled = PagoFacilGatewayPort.escalarMontoPagoFacil(transaction.monto().amount());
        boolean amountMatches = consulta.amount() != null
                && (consulta.amount().compareTo(scaled) == 0 || consulta.amount().compareTo(transaction.monto().amount()) == 0);
        if (!amountMatches) {
            throw new BusinessRuleException("Monto confirmado no coincide con la cuota");
        }

        if (consulta.companyReference() != null && !consulta.companyReference().isBlank()
                && transaction.companyTransactionId() != null
                && !consulta.companyReference().equals(transaction.companyTransactionId())) {
            throw new BusinessRuleException("La transacción pertenece a otra orden de cobro");
        }

        if (!"GENERADO".equals(transaction.estado()) || transaction.usuarioCajeroId() == null
                || transaction.ciNitCliente() == null || transaction.razonSocialCliente() == null) {
            throw new BusinessRuleException("La transacción QR no admite confirmación");
        }

        var now = OffsetDateTime.now(clock);
        var receipt = payment.registrar(transaction.cuotaId(), transaction.usuarioCajeroId(), MetodoPago.QR_PAGOFACIL,
                transaction.ciNitCliente(), transaction.razonSocialCliente());
        transactions.guardar(transaction.confirmar(now));
        return receipt;
    }
}
