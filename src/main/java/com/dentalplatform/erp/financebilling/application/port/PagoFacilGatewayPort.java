package com.dentalplatform.erp.financebilling.application.port;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.OffsetDateTime;

public interface PagoFacilGatewayPort {
    void exigirMock();
    MockQr generar();
    default void exigirDisponible() { exigirMock(); }
    default boolean esMock() { return true; }
    default QrGenerado generar(SolicitudQr request) {
        var qr = generar();
        return new QrGenerado(qr.reference(), qr.imageBase64(), null);
    }
    default ConsultaTransaccion consultarTransaccion(String reference) { throw new UnsupportedOperationException(); }

    /** Provider boundary only: never overwrite the ERP installment/receipt amount. */
    static BigDecimal escalarMontoPagoFacil(BigDecimal original) {
        if (original == null || original.signum() <= 0) { throw new IllegalArgumentException("Monto PagoFácil inválido"); }
        return original.divide(BigDecimal.valueOf(1000), 2, RoundingMode.HALF_UP).max(new BigDecimal("0.01"));
    }
    record MockQr(String reference, String imageBase64) { }
    record SolicitudQr(String companyTransactionId, BigDecimal montoOriginalBs, String ciNit, String nombre,
                       String clientCode, String concepto) { }
    record QrGenerado(String reference, String imageBase64, OffsetDateTime expiration) { }
    record ConsultaTransaccion(boolean pagado, BigDecimal amount, Integer currencyId,
                               String providerReference, String companyReference) { }
}
