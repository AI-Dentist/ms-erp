package com.dentalplatform.erp.financebilling.infrastructure.external;

import com.dentalplatform.erp.financebilling.application.port.N8nNotificationPort;
import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.Map;

/** Opt-in receipt signal, not a patient/clinical payload. No automatic retries or durable outbox. */
@Component
public class N8nWebhookAdapter implements N8nNotificationPort {
    private final boolean enabled;
    private final String url;
    private final RestClient client;

    public N8nWebhookAdapter(@Value("${erp.n8n.enabled:false}") boolean enabled,
                             @Value("${erp.n8n.webhook-url:}") String url,
                             @Value("${erp.n8n.connect-timeout-ms:1000}") int connectTimeout,
                             @Value("${erp.n8n.read-timeout-ms:2000}") int readTimeout) {
        this.enabled = enabled;
        this.url = url;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(bounded(connectTimeout));
        factory.setReadTimeout(bounded(readTimeout));
        client = RestClient.builder().requestFactory(factory).build();
    }

    private static int bounded(int timeout) { return Math.clamp(timeout, 100, 5000); }

    public void notificar(Recibo receipt) {
        if (!enabled) { return; }
        client.post().uri(url).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("event", "recibo.emitido", "reciboId", receipt.id(),
                        "numeroRecibo", receipt.numeroRecibo()))
                .retrieve().toBodilessEntity();
    }
}
