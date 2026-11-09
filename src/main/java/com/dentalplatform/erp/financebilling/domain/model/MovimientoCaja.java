package com.dentalplatform.erp.financebilling.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import java.time.OffsetDateTime;
import java.util.UUID;

public record MovimientoCaja(UUID id, UUID cuotaId, UUID usuarioCajeroId, TipoMovimientoCaja tipoMovimiento,
                             String categoria, MetodoPago metodoPago, Money monto, String descripcion,
                             OffsetDateTime fechaMovimiento) {
    public static MovimientoCaja crear(UUID cuotaId, UUID cajeroId, TipoMovimientoCaja tipo, String categoria,
                                       MetodoPago metodo, Money monto, String descripcion, OffsetDateTime now) {
        if (cajeroId == null || tipo == null || metodo == null || monto == null || monto.isZero()) {
            throw new BusinessRuleException("El movimiento requiere cajero, tipo, método y monto mayor a cero");
        }
        return new MovimientoCaja(UUID.randomUUID(), cuotaId, cajeroId, tipo,
                texto(categoria, "categoría", 50), metodo, monto, texto(descripcion, "descripción", Integer.MAX_VALUE), now);
    }

    public static String texto(String value, String field, int max) {
        if (value == null || value.isBlank() || value.trim().length() > max) {
            throw new BusinessRuleException("El campo " + field + " es obligatorio y admite hasta " + max + " caracteres");
        }
        return value.trim();
    }
}
