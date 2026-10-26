package com.dentalplatform.erp.financebilling.application.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for how the agent answers: a real chat model (OpenAI, or Gemini through its
 * OpenAI-compatible base URL) when an API key is configured; otherwise the deterministic planner, but ONLY in the
 * development profiles {@code dev} and {@code local}; any other profile without a key is NOT_CONFIGURADO.
 */
@Component
public class AgentProvider {
    public enum Modo { IA, RESPALDO, NO_CONFIGURADO }

    public static final String ETIQUETA_RESPALDO = "[Respaldo determinístico — sin IA]";
    public static final String MENSAJE_NO_CONFIGURADO =
            "Agente IA no configurado: defina OPENAI_API_KEY (o el proveedor compatible) para usar el agente";

    private final Environment environment;
    private final String apiKey;

    public AgentProvider(Environment environment, @Value("${spring.ai.openai.api-key:}") String apiKey) {
        this.environment = environment;
        this.apiKey = apiKey;
    }

    public Modo modo() {
        if (!apiKey.isBlank() && !apiKey.equals("not-configured")) { return Modo.IA; }
        return environment.acceptsProfiles(Profiles.of("dev", "local")) ? Modo.RESPALDO : Modo.NO_CONFIGURADO;
    }
}
