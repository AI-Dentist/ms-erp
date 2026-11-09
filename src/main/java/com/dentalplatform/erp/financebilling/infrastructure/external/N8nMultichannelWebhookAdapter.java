package com.dentalplatform.erp.financebilling.infrastructure.external;

import com.dentalplatform.erp.financebilling.application.port.MultichannelNotificationPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

/** S17-only transport. The legacy N8nWebhookAdapter keeps its original failure behavior. */
@Component
public class N8nMultichannelWebhookAdapter implements MultichannelNotificationPort {
    private final boolean enabled;
    private final String url;
    private final String secret;
    private final RestClient client;

    public N8nMultichannelWebhookAdapter(@Value("${erp.n8n.enabled:false}") boolean enabled,
            @Value("${erp.n8n.webhook-url:http://localhost:5678/webhook/erp-notificaciones}") String url,
            @Value("${erp.n8n.webhook-secret:}") String secret,
            @Value("${erp.n8n.connect-timeout-ms:1000}") int connectTimeout,
            @Value("${erp.n8n.read-timeout-ms:2000}") int readTimeout) {
        this.enabled = enabled;
        this.url = url;
        this.secret = secret;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.clamp(connectTimeout, 100, 5000));
        factory.setReadTimeout(Math.clamp(readTimeout, 100, 5000));
        client = RestClient.builder().requestFactory(factory).build();
    }
    @Override public boolean habilitado() { return enabled && url != null && !url.isBlank() && secret != null && !secret.isBlank(); }
    @Override public void enviar(Payload payload) {
        if (!habilitado()) { throw new IllegalStateException("Multichannel n8n transport is disabled"); }
        try {
            client.post().uri(url).header("X-ERP-Webhook-Secret", secret).contentType(MediaType.APPLICATION_JSON)
                    .body(payload).retrieve().toBodilessEntity();
        } catch (ResourceAccessException e) { throw new UnavailableException(e); }
    }
}
