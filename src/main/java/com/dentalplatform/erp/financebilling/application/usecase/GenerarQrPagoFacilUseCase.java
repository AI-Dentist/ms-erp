package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.port.CurrentActorPort;
import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.financebilling.application.port.PagoFacilGatewayPort;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.financebilling.domain.repository.*;
import com.dentalplatform.erp.shared.domain.exception.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

@Service
public class GenerarQrPagoFacilUseCase {
    private final OrdenCobroRepository orders;
    private final TransaccionQrRepository transactions;
    private final PagoFacilGatewayPort gateway;
    private final UsuarioRepository users;
    private final CurrentActorPort actor;
    private final Clock clock;
    private final int expirationMinutes;

    public GenerarQrPagoFacilUseCase(OrdenCobroRepository orders, TransaccionQrRepository transactions,
                                    PagoFacilGatewayPort gateway, UsuarioRepository users, CurrentActorPort actor,
                                    Clock clock, @Value("${erp.pagofacil.qr-expiration-minutes:30}") int expirationMinutes) {
        this.orders = orders;
        this.transactions = transactions;
        this.gateway = gateway;
        this.users = users;
        this.actor = actor;
        this.clock = clock;
        this.expirationMinutes = expirationMinutes;
    }

    @Transactional
    public TransaccionQr ejecutar(UUID quotaId, String ciNit, String name) {
        gateway.exigirDisponible();
        var current = actor.actual();
        var cashier = users.buscarPorKeycloakIdOUsername(current.keycloakId().orElse(null), current.username())
                .filter(u -> u.isActivo() && (u.getRol().codigo().equals("ADMIN") || u.getRol().codigo().equals("CAJERO")))
                .orElseThrow(() -> new BusinessRuleException("No existe cajero activo para el usuario autenticado"));
        var order = orders.bloquearPorCuotaId(quotaId)
                .orElseThrow(() -> new ResourceNotFoundException("La cuota no existe"));
        var quota = order.getCuotas().stream().filter(c -> c.id().equals(quotaId)).findFirst().orElseThrow();
        if (order.getEstado() == EstadoOrdenCobro.ANULADO
                || (quota.estado() != EstadoCuota.PENDIENTE && quota.estado() != EstadoCuota.VENCIDA)
                || quota.montoCuota().isZero()) {
            throw new BusinessRuleException("La cuota no admite cobro QR");
        }
        if (expirationMinutes < 1 || expirationMinutes > 1440) {
            throw new BusinessRuleException("Expiración QR inválida");
        }
        ciNit = MovimientoCaja.texto(ciNit, "CI/NIT", 30);
        name = MovimientoCaja.texto(name, "razón social", 150);
        String companyReference = "ERP-QR-" + UUID.randomUUID();
        var qr = gateway.generar(new PagoFacilGatewayPort.SolicitudQr(companyReference, quota.montoCuota().amount(),
                ciNit, name, order.getPacienteId().toString(), "Cuota odontológica"));
        var now = OffsetDateTime.now(clock);
        var transaction = TransaccionQr.generar(quotaId, quota.montoCuota(), qr.reference(), qr.imageBase64(),
                ciNit, name, cashier.getId(), now, qr.expiration() == null ? now.plusMinutes(expirationMinutes) : qr.expiration());
        return transactions.guardar(gateway.esMock() ? transaction : transaction.conCorrelacionEmpresa(companyReference));
    }
}
