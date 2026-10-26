package com.dentalplatform.erp.financebilling.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import java.util.UUID;

public record OrdenCobroItem(UUID id, UUID servicioId, String piezaDental, int cantidad,
                             Money precioUnitario, Money subtotal) {
    public static OrdenCobroItem crear(UUID servicioId, String piezaDental, int cantidad, Money precio) {
        if (servicioId == null || precio == null || cantidad < 1) {
            throw new BusinessRuleException("Servicio, precio y cantidad positiva son obligatorios");
        }
        if (piezaDental != null && piezaDental.length() > 10) {
            throw new BusinessRuleException("La pieza dental admite hasta 10 caracteres");
        }
        return new OrdenCobroItem(UUID.randomUUID(), servicioId, piezaDental, cantidad, precio, precio.times(cantidad));
    }
}
