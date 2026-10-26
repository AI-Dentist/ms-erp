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
public class ConfirmarPagoQrWebhookUseCase {
    private final TransaccionQrRepository transactions;
    private final OrdenCobroRepository orders;
    private final ReciboRepository receipts;
    private final RegistrarCobroCuotaService payment;
    private final PagoFacilGatewayPort gateway;
    private final Clock clock;

    public ConfirmarPagoQrWebhookUseCase(TransaccionQrRepository transactions, OrdenCobroRepository orders,
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
    public Recibo ejecutar(String reference, BigDecimal amount, OffsetDateTime confirmedAt) {
        gateway.exigirMock();
        var now = OffsetDateTime.now(clock);
        if (reference == null || !reference.matches("MOCK-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                || amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || confirmedAt == null || confirmedAt.isAfter(now.plusSeconds(60))) {
            throw new BusinessRuleException("Referencia, monto o fecha de confirmación mock inválidos");
        }
        var initial = transactions.buscar(reference)
                .orElseThrow(() -> new ResourceNotFoundException("La transacción QR no existe"));
        // Global lock order: ORDER then QR, including generation, cash and callback paths.
        orders.bloquearPorCuotaId(initial.cuotaId())
                .orElseThrow(() -> new ResourceNotFoundException("La cuota no existe"));
        var transaction = transactions.bloquear(reference).orElseThrow();
        if (amount.compareTo(transaction.monto().amount()) != 0
                || confirmedAt.isBefore(transaction.fechaCreacion().minusSeconds(60))) {
            throw new BusinessRuleException("Monto o fecha no coincide con la transacción QR");
        }
        if ("CONFIRMADO".equals(transaction.estado())) {
            return receipts.buscarPorCuota(transaction.cuotaId()).orElseThrow();
        }
        if (transaction.fechaExpiracion() == null || !now.isBefore(transaction.fechaExpiracion())
                || confirmedAt.isAfter(transaction.fechaExpiracion())) { throw new QrExpiredException(); }
        if (!"GENERADO".equals(transaction.estado()) || transaction.usuarioCajeroId() == null
                || transaction.ciNitCliente() == null || transaction.razonSocialCliente() == null) {
            throw new BusinessRuleException("La transacción QR no admite confirmación mock");
        }
        var receipt = payment.registrar(transaction.cuotaId(), transaction.usuarioCajeroId(), MetodoPago.QR_PAGOFACIL,
                transaction.ciNitCliente(), transaction.razonSocialCliente());
        transactions.guardar(transaction.confirmar(now));
        return receipt;
    }
}
