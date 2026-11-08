package com.dentalplatform.erp.financebilling.infrastructure.external;

import com.dentalplatform.erp.financebilling.application.port.N8nNotificationPort;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in webhook signals. Receipts carry no patient data; collection reminders carry contact data by design. */
@Component
public class N8nWebhookAdapter implements N8nNotificationPort {
    private final boolean enabled;
    private final String url;
    private final String secret;
    private final RestClient client;

    public N8nWebhookAdapter(boolean enabled, String url, int connectTimeout, int readTimeout) {
        this(enabled, url, connectTimeout, readTimeout, "");
    }

    @org.springframework.beans.factory.annotation.Autowired
    public N8nWebhookAdapter(@Value("${erp.n8n.enabled:false}") boolean enabled,
                             @Value("${erp.n8n.webhook-url:}") String url,
                             @Value("${erp.n8n.connect-timeout-ms:1000}") int connectTimeout,
                             @Value("${erp.n8n.read-timeout-ms:2000}") int readTimeout,
                             @Value("${erp.n8n.webhook-secret:}") String secret) {
        this.enabled = enabled;
        this.url = url;
        this.secret = secret;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(bounded(connectTimeout));
        factory.setReadTimeout(bounded(readTimeout));
        client = RestClient.builder().requestFactory(factory).build();
    }

    private static int bounded(int timeout) { return Math.clamp(timeout, 100, 5000); }

    @Override
    public boolean habilitado() { return enabled && url != null && !url.isBlank() && secret != null && !secret.isBlank(); }

    @Override
    public void notificar(Recibo receipt) {
        if (!habilitado()) { return; }
        client.post().uri(url).header("X-ERP-Webhook-Secret", secret).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("event", "recibo.emitido", "reciboId", receipt.id(),
                        "numeroRecibo", receipt.numeroRecibo()))
                .retrieve().toBodilessEntity();
    }

    @Override
    public void notificarCobranza(CobranzaAlerta alert) {
        if (!habilitado()) { throw new IllegalStateException("n8n deshabilitado"); }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("event", "cobranza.recordatorio");
        body.put("cuotaId", alert.cuotaId());
        body.put("ordenCobroId", alert.ordenCobroId());
        body.put("pacienteId", alert.pacienteId());
        body.put("pacienteNombre", alert.nombrePaciente());
        body.put("telefono", alert.telefonoWhatsapp());
        body.put("fechaVencimiento", alert.vencimiento());
        body.put("concepto", "Cuota " + alert.numeroCuota() + " de orden " + alert.ordenCobroId());
        body.put("nombrePaciente", alert.nombrePaciente());
        body.put("telefonoWhatsapp", alert.telefonoWhatsapp());
        body.put("numeroCuota", alert.numeroCuota());
        body.put("montoBs", alert.montoBs());
        body.put("vencimiento", alert.vencimiento());
        body.put("diasAtraso", alert.diasAtraso());
        if (alert.qr() != null) {
            body.put("qrPagoFacil", Map.of("transaccionId", alert.qr().transaccionId(),
                    "expiraEn", alert.qr().expiraEn(), "imagenBase64", alert.qr().imagenBase64()));
        }
        client.post().uri(url).header("X-ERP-Webhook-Secret", secret).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
    }
}
