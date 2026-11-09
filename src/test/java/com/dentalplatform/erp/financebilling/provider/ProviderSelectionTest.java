package com.dentalplatform.erp.financebilling.provider;

import com.dentalplatform.erp.financebilling.application.agent.AgentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.*;

class ProviderSelectionTest {
    private MockEnvironment env(String... values) throws Exception {
        var env = new MockEnvironment();
        for (int i = 0; i < values.length; i += 2) env.setProperty(values[i], values[i + 1]);
        env.getPropertySources().addLast(new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml")).getFirst());
        return env;
    }

    @Test void openAiDefaultsAndExistingOverridesRemainAvailable() throws Exception {
        var e = env("OPENAI_API_KEY", "open-key", "ERP_AI_MODEL", "legacy-model");
        assertThat(e.getProperty("spring.ai.openai.chat.api-key")).isEqualTo("open-key");
        assertThat(e.getProperty("spring.ai.openai.chat.options.model")).isEqualTo("legacy-model");
        assertThat(new AgentProvider(e, e.getProperty("spring.ai.openai.chat.api-key")).modo()).isEqualTo(AgentProvider.Modo.IA);
    }

    @Test void geminiSelectsItsOwnKeyModelAndCompatibleEndpoint() throws Exception {
        var e = env("AI_PROVIDER", "gemini", "OPENAI_API_KEY", "other-key", "GEMINI_API_KEY", "gemini-key");
        assertThat(e.getProperty("spring.ai.openai.chat.api-key")).isEqualTo("gemini-key");
        assertThat(e.getProperty("spring.ai.openai.chat.options.model")).isEqualTo("gemini-2.5-flash");
        assertThat(e.getProperty("spring.ai.openai.chat.base-url")).isEqualTo("https://generativelanguage.googleapis.com/v1beta/openai");
    }

    @Test void missingSelectedKeyFallsBackEvenInProduction() throws Exception {
        var e = env("AI_PROVIDER", "gemini", "OPENAI_API_KEY", "other-key", "GEMINI_API_KEY", " ");
        e.setActiveProfiles("prod");
        assertThat(new AgentProvider(e, e.getProperty("spring.ai.openai.chat.api-key")).modo()).isEqualTo(AgentProvider.Modo.RESPALDO);
    }

    @Test void explicitModelsAndCompatibilityOverridesAreRespected() throws Exception {
        var open = env("OPENAI_MODEL", "open-model", "spring.ai.openai.base-url", "http://localhost:1234/v1",
                "spring.ai.openai.api-key", "integration-key");
        assertThat(open.getProperty("spring.ai.openai.chat.options.model")).isEqualTo("open-model");
        assertThat(open.getProperty("spring.ai.openai.chat.base-url")).isEqualTo("http://localhost:1234/v1");
        assertThat(open.getProperty("spring.ai.openai.chat.api-key")).isEqualTo("integration-key");
        var gemini = env("AI_PROVIDER", "gemini", "GEMINI_MODEL", "gemini-custom", "GEMINI_API_KEY", "PEGAR_AQUI_TU_GEMINI_API_KEY");
        assertThat(gemini.getProperty("spring.ai.openai.chat.options.model")).isEqualTo("gemini-custom");
        assertThat(new AgentProvider(gemini, gemini.getProperty("spring.ai.openai.chat.api-key")).modo())
                .isEqualTo(AgentProvider.Modo.RESPALDO);
    }

    @Test void unknownProviderIsRejectedRatherThanUsingAnother() throws Exception {
        var e = env("AI_PROVIDER", "unknown");
        assertThatThrownBy(() -> new AgentProvider(e, "key")).isInstanceOf(IllegalArgumentException.class);
    }
}
