package com.dentalplatform.erp.financebilling.application.agent;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Single source of truth for how the agent answers: a real chat model (OpenAI, or Gemini through its
 * OpenAI-compatible base URL) when an API key is configured; otherwise the labeled deterministic planner.
 */
@Component
public class AgentProvider {
    public enum Modo { IA, RESPALDO, NO_CONFIGURADO }

    public static final String ETIQUETA_RESPALDO = "[Respaldo determinístico — sin IA]";
    public static final String MENSAJE_NO_CONFIGURADO =
            "Agente IA no configurado: defina OPENAI_API_KEY (o el proveedor compatible) para usar el agente";

    private final String apiKey;

    public AgentProvider(Environment environment, @Value("${spring.ai.openai.chat.api-key:}") String apiKey) {
        String provider = environment.getProperty("AI_PROVIDER", "openai");
        if (!provider.equals("openai") && !provider.equals("gemini")) {
            throw new IllegalArgumentException("AI_PROVIDER debe ser openai o gemini");
        }
        this.apiKey = apiKey;
    }

    public Modo modo() {
        String key = apiKey.trim();
        return key.isEmpty() || key.equals("not-configured") || key.startsWith("PEGAR_AQUI_")
                ? Modo.RESPALDO : Modo.IA;
    }
}
