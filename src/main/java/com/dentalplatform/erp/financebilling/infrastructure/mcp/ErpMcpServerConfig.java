package com.dentalplatform.erp.financebilling.infrastructure.mcp;

import com.dentalplatform.erp.financebilling.application.agent.ErpMcpToolsService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One security-proxied callback set for both MCP and the agent's model adapter. */
@Configuration
class ErpMcpServerConfig {
    @Bean
    ToolCallbackProvider erpToolCallbackProvider(ErpMcpToolsService tools, JsonMapper json) {
        var provider = MethodToolCallbackProvider.builder().toolObjects(tools).build();
        ToolCallback[] callbacks = Arrays.stream(provider.getToolCallbacks()).map(callback -> {
            if (!Set.of("dispararNotificacionMulticanal", "dispararAlertaCobranzaWhatsapp")
                    .contains(callback.getToolDefinition().name())) { return callback; }
            // Object accepts both representations at runtime; publish the real union, not an object-only schema.
            var schema = (ObjectNode) json.readTree(callback.getToolDefinition().inputSchema());
            ((ObjectNode) schema.get("properties")).set("canales", json.valueToTree(Map.of(
                    "description", "Lista de WHATSAPP/TELEGRAM/GMAIL, texto CSV o TODOS; omitido = todos",
                    "anyOf", List.of(Map.of("type", "string"), Map.of("type", "array", "items", Map.of("type", "string")), Map.of("type", "null")))));
            var definition = ToolDefinition.builder().name(callback.getToolDefinition().name())
                    .description(callback.getToolDefinition().description()).inputSchema(json.writeValueAsString(schema)).build();
            return new ToolCallback() {
                @Override public ToolDefinition getToolDefinition() { return definition; }
                @Override public ToolMetadata getToolMetadata() { return callback.getToolMetadata(); }
                @Override public String call(String input) { return callback.call(input); }
                @Override public String call(String input, ToolContext context) { return callback.call(input, context); }
            };
        }).toArray(ToolCallback[]::new);
        return () -> callbacks.clone();
    }
}
