package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.command.ConfigurarPlanPagosCommand;
import com.dentalplatform.erp.financebilling.application.command.ReprogramarPlanPagosCommand;
import com.dentalplatform.erp.financebilling.domain.model.ModalidadPago;
import com.dentalplatform.erp.financebilling.domain.model.OrdenCobro;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.temporal.ChronoUnit;

/**
 * Partial-input adapter around the existing plan use case, including its domain validation, persistence and audit.
 * Count-only changes infer CONTADO/CREDITO. Unspecified interval is inferred from the first two installments;
 * a single-installment plan has no observable interval, so it defaults to one day (the domain minimum).
 * Existing QR foreign keys may prevent replacing a plan: never delete a QR/payment snapshot to bypass that guard.
 */
@Service
public class ReprogramarPlanPagosUseCase {
    private final OrdenCobroRepository orders;
    private final ConfigurarPlanPagosUseCase configure;

    public ReprogramarPlanPagosUseCase(OrdenCobroRepository orders, ConfigurarPlanPagosUseCase configure) {
        this.orders = orders;
        this.configure = configure;
    }

    @PreAuthorize("hasAnyRole('ADMIN','CAJERO','RECEPCIONISTA')")
    @Transactional
    public OrdenCobro ejecutar(ReprogramarPlanPagosCommand c) {
        if (c == null || c.ordenCobroId() == null || (c.modalidadPago() == null && c.descuentoBs() == null
                && c.cantidadCuotas() == null && c.fechaPrimeraCuota() == null && c.intervaloDias() == null)) {
            throw new BusinessRuleException("La reprogramación requiere una orden y al menos un cambio del plan");
        }
        var order = orders.bloquearPorId(c.ordenCobroId())
                .orElseThrow(() -> new ResourceNotFoundException("La orden de cobro no existe"));
        var cuotas = order.getCuotas();
        if (cuotas.isEmpty()) { throw new BusinessRuleException("La orden no tiene un plan de pagos para reprogramar"); }
        int count = c.cantidadCuotas() == null ? cuotas.size() : c.cantidadCuotas();
        var modalidad = c.modalidadPago() != null ? c.modalidadPago() : c.cantidadCuotas() == null
                ? order.getModalidadPago() : count == 1 ? ModalidadPago.CONTADO : ModalidadPago.CREDITO;
        int interval = c.intervaloDias() != null ? c.intervaloDias() : cuotas.size() < 2 ? 1
                : Math.toIntExact(ChronoUnit.DAYS.between(cuotas.get(0).fechaVencimiento(), cuotas.get(1).fechaVencimiento()));
        return configure.ejecutar(new ConfigurarPlanPagosCommand(order.getId(), modalidad,
                c.descuentoBs() == null ? order.getDescuento().amount() : c.descuentoBs(), count,
                c.fechaPrimeraCuota() == null ? cuotas.getFirst().fechaVencimiento() : c.fechaPrimeraCuota(), interval));
    }
}
