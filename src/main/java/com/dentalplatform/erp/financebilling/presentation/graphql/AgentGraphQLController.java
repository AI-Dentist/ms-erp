package com.dentalplatform.erp.financebilling.presentation.graphql;

import com.dentalplatform.erp.financebilling.application.agent.AgenteInteraccionResponse;
import com.dentalplatform.erp.financebilling.application.port.McpServerInfoPort.EstadoServidorMcp;
import com.dentalplatform.erp.financebilling.application.usecase.ErpCopilotAgentUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.ListarHistorialAgenteUseCase;
import com.dentalplatform.erp.financebilling.application.usecase.ObtenerEstadoServidorMcpUseCase;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;

/** The agent's only GraphQL surface (replaces the removed {@code generarReporteInteligente}). */
@Controller
public class AgentGraphQLController {
    private final ErpCopilotAgentUseCase agent;
    private final ListarHistorialAgenteUseCase history;
    private final ObtenerEstadoServidorMcpUseCase status;

    public AgentGraphQLController(ErpCopilotAgentUseCase agent, ListarHistorialAgenteUseCase history,
                                  ObtenerEstadoServidorMcpUseCase status) {
        this.agent = agent;
        this.history = history;
        this.status = status;
    }

    @MutationMapping
    @PreAuthorize("hasAnyRole('ADMIN','CAJERO','RECEPCIONISTA')")
    public AgenteInteraccionResponse ejecutarAgenteErp(@Argument String instruccion,
                                                       @Argument boolean modoAutonomoMonitoreo) {
        return agent.ejecutar(instruccion, modoAutonomoMonitoreo);
    }

    @QueryMapping
    @PreAuthorize("hasAnyRole('ADMIN','CAJERO','RECEPCIONISTA','ODONTOLOGO')")
    public List<AgenteInteraccionResponse> listarHistorialAgente(@Argument int limite) {
        return history.ejecutar(limite);
    }

    @QueryMapping
    @PreAuthorize("hasRole('ADMIN')")
    public EstadoServidorMcp obtenerEstadoServidorMcp() { return status.ejecutar(); }
}
