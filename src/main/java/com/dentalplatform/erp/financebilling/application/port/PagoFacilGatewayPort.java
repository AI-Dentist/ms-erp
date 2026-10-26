package com.dentalplatform.erp.financebilling.application.port;

public interface PagoFacilGatewayPort {
    void exigirMock();
    MockQr generar();
    record MockQr(String reference, String imageBase64) { }
}
