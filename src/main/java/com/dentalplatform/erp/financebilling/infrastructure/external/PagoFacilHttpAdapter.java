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

/** No HTTP provider protocol is implemented. The image is deliberately NOT a scannable payment QR. */
@Component
public class PagoFacilHttpAdapter implements PagoFacilGatewayPort {
    private final boolean mock;

    public PagoFacilHttpAdapter(@Value("${erp.pagofacil.mock-enabled:true}") boolean mock) { this.mock = mock; }

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
