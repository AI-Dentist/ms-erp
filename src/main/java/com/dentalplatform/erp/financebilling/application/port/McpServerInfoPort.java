package com.dentalplatform.erp.financebilling.application.port;

import java.util.List;

/** Read-only view of the embedded MCP server (Streamable HTTP at /mcp). */
public interface McpServerInfoPort {
    EstadoServidorMcp estado();

    /** GraphQL {@code McpServerStatus}. */
    record EstadoServidorMcp(boolean activo, String protocolVersion, List<McpToolMetadata> toolsRegistradas) { }

    /** GraphQL {@code McpToolMetadata}. */
    record McpToolMetadata(String nombre, String categoria, String descripcion) { }
}
