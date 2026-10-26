package com.dentalplatform.erp.financebilling.domain.model;

import com.dentalplatform.erp.shared.domain.vo.Money;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

public record Recibo(UUID id, UUID movimientoCajaId, String numeroRecibo, String ciNitCliente,
                     String razonSocialCliente, String concepto, Money montoTotal, String pdfUrlS3,
                     boolean enviadoWhatsapp, OffsetDateTime fechaEmision) {
    public static Recibo emitir(MovimientoCaja movimiento, long sequence, String ciNit, String razonSocial) {
        return new Recibo(UUID.randomUUID(), movimiento.id(), String.format(Locale.ROOT, "REC-%d-%06d",
                movimiento.fechaMovimiento().getYear(), sequence), MovimientoCaja.texto(ciNit, "CI/NIT", 30),
                MovimientoCaja.texto(razonSocial, "razón social", 150), movimiento.descripcion(), movimiento.monto(),
                null, false, movimiento.fechaMovimiento());
    }

    public Recibo conPdf(String url) {
        return new Recibo(id, movimientoCajaId, numeroRecibo, ciNitCliente, razonSocialCliente, concepto,
                montoTotal, url, enviadoWhatsapp, fechaEmision);
    }
}
