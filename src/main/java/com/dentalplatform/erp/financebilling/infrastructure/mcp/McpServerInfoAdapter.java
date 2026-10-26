package com.dentalplatform.erp.financebilling.infrastructure.mcp;

import com.dentalplatform.erp.financebilling.application.agent.ErpMcpToolsService;
import com.dentalplatform.erp.financebilling.application.agent.ToolCategory;
import com.dentalplatform.erp.financebilling.application.port.McpServerInfoPort;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Status comes from the live MCP server: its registered tools (name and description as published) and the newest
 * protocol version its Streamable HTTP transport speaks. The category is declared next to each tool method.
 */
@Component
class McpServerInfoAdapter implements McpServerInfoPort {
    private final ObjectProvider<McpSyncServer> server;
    private final ObjectProvider<McpStreamableServerTransportProvider> transport;
    private final boolean enabled;
    private final Map<String, String> categories = new HashMap<>();

    McpServerInfoAdapter(ObjectProvider<McpSyncServer> server,
                         ObjectProvider<McpStreamableServerTransportProvider> transport,
                         @Value("${spring.ai.mcp.server.enabled:true}") boolean enabled) {
        this.server = server;
        this.transport = transport;
        this.enabled = enabled;
        for (Method method : ErpMcpToolsService.class.getMethods()) {
            Tool tool = method.getAnnotation(Tool.class);
            ToolCategory category = method.getAnnotation(ToolCategory.class);
            if (tool != null && category != null) {
                categories.put(tool.name().isBlank() ? method.getName() : tool.name(), category.value());
            }
        }
    }

    @Override
    public EstadoServidorMcp estado() {
        McpSyncServer mcp = server.getIfAvailable();
        boolean active = enabled && mcp != null && transport.getIfAvailable() != null;
        List<McpToolMetadata> tools = !active ? List.of() : mcp.listTools().stream()
                .map(t -> new McpToolMetadata(t.name(), categories.getOrDefault(t.name(), "OTRA"),
                        t.description() == null ? "" : t.description()))
                .sorted(Comparator.comparing(McpToolMetadata::categoria).thenComparing(McpToolMetadata::nombre)).toList();
        return new EstadoServidorMcp(active, active ? protocolVersion() : "no disponible", tools);
    }

    private String protocolVersion() {
        var provider = transport.getIfAvailable();
        List<String> versions = provider == null ? List.of() : provider.protocolVersions();
        // ISO dates sort chronologically; the newest one is what the server negotiates first.
        return versions.stream().max(Comparator.naturalOrder()).orElse(ProtocolVersions.MCP_2025_06_18);
    }
}
