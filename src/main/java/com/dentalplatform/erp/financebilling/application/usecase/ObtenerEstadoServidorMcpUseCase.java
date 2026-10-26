package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.port.McpServerInfoPort;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

@Service
public class ObtenerEstadoServidorMcpUseCase {
    private final McpServerInfoPort info;

    public ObtenerEstadoServidorMcpUseCase(McpServerInfoPort info) { this.info = info; }

    @PreAuthorize("hasRole('ADMIN')")
    public McpServerInfoPort.EstadoServidorMcp ejecutar() { return info.estado(); }
}
