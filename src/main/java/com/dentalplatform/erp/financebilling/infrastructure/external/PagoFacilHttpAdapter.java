package com.dentalplatform.erp.financebilling.infrastructure.external;

import com.dentalplatform.erp.financebilling.application.port.PagoFacilGatewayPort;
import com.dentalplatform.erp.financebilling.application.usecase.UnsupportedPagoFacilIntegrationException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.UUID;

/** Explicit mock mode retains the non-scannable image; configured live mode uses QR Master v1.1.0. */
@Component
public class PagoFacilHttpAdapter implements PagoFacilGatewayPort {
    private final boolean mock;
    private final PagoFacilMasterQrClient client;
    private final boolean configured;

    /** Compatibility constructor: no credential-bearing integration is implicitly enabled. */
    public PagoFacilHttpAdapter(boolean mock) { this(mock, "", "", "", 0, "", java.time.Clock.systemUTC()); }

    @org.springframework.beans.factory.annotation.Autowired
    public PagoFacilHttpAdapter(@Value("${erp.pagofacil.mock-enabled:true}") boolean mock,
            @Value("${erp.pagofacil.base-url:https://masterqr.pagofacil.com.bo/api/services/v2}") String base,
            @Value("${erp.pagofacil.token-service:}") String service,
            @Value("${erp.pagofacil.token-secret:}") String secret,
            @Value("${erp.pagofacil.payment-method-id:0}") int method,
            @Value("${erp.pagofacil.callback-url:}") String callback, java.time.Clock clock) {
        this.mock = mock;
        this.configured = !service.isBlank() && !secret.isBlank();
        this.client = new PagoFacilMasterQrClient(base, service, secret, method, callback, clock);
    }

    @Override public boolean esMock() { return mock; }
    @Override public void exigirDisponible() {
        if (!mock && !configured) { throw new UnsupportedPagoFacilIntegrationException(); }
    }
    @Override public QrGenerado generar(SolicitudQr request) {
        exigirDisponible();
        return mock ? PagoFacilGatewayPort.super.generar(request) : client.generar(request);
    }
    @Override public ConsultaTransaccion consultarTransaccion(String reference) {
        exigirDisponible();
        if (mock) { throw new UnsupportedPagoFacilIntegrationException(); }
        return client.consultar(reference);
    }

    public void exigirMock() {
        if (!mock) { throw new UnsupportedPagoFacilIntegrationException(); }
    }

    public MockQr generar() {
        exigirMock();
        BufferedImage image = new BufferedImage(440, 140, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, 440, 140);
            graphics.setColor(Color.RED);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 23));
            graphics.drawString("MOCK - NO PAGAR", 30, 60);
            graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 17));
            graphics.drawString("SIMULACION LOCAL - NO VALIDO", 30, 100);
        } finally { graphics.dispose(); }
        try (var bytes = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", bytes);
            return new MockQr("MOCK-" + UUID.randomUUID(), Base64.getEncoder().encodeToString(bytes.toByteArray()));
        } catch (IOException e) { throw new IllegalStateException("No se pudo crear imagen mock", e); }
    }
}
