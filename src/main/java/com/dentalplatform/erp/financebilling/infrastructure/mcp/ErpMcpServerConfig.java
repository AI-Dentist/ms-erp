package com.dentalplatform.erp.financebilling.infrastructure.mcp;

import com.dentalplatform.erp.financebilling.application.agent.ErpMcpToolsService;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the ERP tools once. The MCP server starter collects every {@link ToolCallbackProvider} bean, and the agent
 * model adapter reads the same bean, so both surfaces expose exactly the same (security-proxied) tool methods.
 */
@Configuration
class ErpMcpServerConfig {
    @Bean
    ToolCallbackProvider erpToolCallbackProvider(ErpMcpToolsService tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }
}
