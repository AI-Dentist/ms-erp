package com.dentalplatform.erp.financebilling.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record CuotaPago(UUID id, int numeroCuota, Money montoCuota, LocalDate fechaVencimiento,
                        EstadoCuota estado, OffsetDateTime fechaPago) {
    public CuotaPago anular() {
        if (estado == EstadoCuota.PAGADA) {
            throw new BusinessRuleException("No se puede anular una cuota pagada");
        }
        return new CuotaPago(id, numeroCuota, montoCuota, fechaVencimiento, EstadoCuota.ANULADA, fechaPago);
    }

    public CuotaPago pagar(OffsetDateTime now) {
        if (estado != EstadoCuota.PENDIENTE) {
            throw new BusinessRuleException("La cuota no está pendiente de pago");
        }
        return new CuotaPago(id, numeroCuota, montoCuota, fechaVencimiento, EstadoCuota.PAGADA, now);
    }
}
