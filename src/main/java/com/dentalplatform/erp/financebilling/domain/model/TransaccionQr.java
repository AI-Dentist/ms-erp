package com.dentalplatform.erp.financebilling.domain.model;

import com.dentalplatform.erp.shared.domain.vo.Money;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Immutable payment snapshot. Customer and cashier are captured at generation, never from a callback. */
public record TransaccionQr(UUID id, UUID cuotaId, String pagofacilTransactionId, String qrImagenBase64,
                            Money monto, String estado, OffsetDateTime fechaCreacion,
                            OffsetDateTime fechaExpiracion, OffsetDateTime fechaConfirmacion,
                            String ciNitCliente, String razonSocialCliente, UUID usuarioCajeroId, String companyTransactionId) {
    /** Retain the original constructor for existing mock/domain callers. */
    public TransaccionQr(UUID id, UUID quota, String reference, String image, Money amount, String status,
                         OffsetDateTime created, OffsetDateTime expires, OffsetDateTime confirmed,
                         String ciNit, String name, UUID cashier) {
        this(id, quota, reference, image, amount, status, created, expires, confirmed, ciNit, name, cashier, null);
    }

    public TransaccionQr conCorrelacionEmpresa(String reference) {
        return new TransaccionQr(id, cuotaId, pagofacilTransactionId, qrImagenBase64, monto, estado,
                fechaCreacion, fechaExpiracion, fechaConfirmacion, ciNitCliente, razonSocialCliente, usuarioCajeroId, reference);
    }
    public static TransaccionQr generar(UUID quota, Money amount, String reference, String image,
                                        String ciNit, String name, UUID cashier, OffsetDateTime now,
                                        OffsetDateTime expires) {
        return new TransaccionQr(UUID.randomUUID(), quota, reference, image, amount, "GENERADO", now, expires,
                null, MovimientoCaja.texto(ciNit, "CI/NIT", 30),
                MovimientoCaja.texto(name, "razón social", 150), cashier);
    }

    public TransaccionQr confirmar(OffsetDateTime now) {
        return new TransaccionQr(id, cuotaId, pagofacilTransactionId, qrImagenBase64, monto, "CONFIRMADO",
                fechaCreacion, fechaExpiracion, now, ciNitCliente, razonSocialCliente, usuarioCajeroId, companyTransactionId);
    }

    public BigDecimal getMontoBs() { return monto.amount(); }
}
