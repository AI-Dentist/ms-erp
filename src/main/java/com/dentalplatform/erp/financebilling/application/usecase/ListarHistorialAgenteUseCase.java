package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.agent.AgentActorResolver;
import com.dentalplatform.erp.financebilling.application.agent.AgentInteractionMapper;
import com.dentalplatform.erp.financebilling.application.agent.AgenteInteraccionResponse;
import com.dentalplatform.erp.financebilling.domain.repository.AgenteInteraccionRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.util.List;

/** ADMIN sees every interaction; any other ERP role sees only its own (matched by authenticated principal name). */
@Service
public class ListarHistorialAgenteUseCase {
    private final AgenteInteraccionRepository history;
    private final AgentActorResolver actor;
    private final AgentInteractionMapper mapper;

    public ListarHistorialAgenteUseCase(AgenteInteraccionRepository history, AgentActorResolver actor,
                                        AgentInteractionMapper mapper) {
        this.history = history;
        this.actor = actor;
        this.mapper = mapper;
    }

    @PreAuthorize("hasAnyRole('ADMIN','CAJERO','RECEPCIONISTA','ODONTOLOGO')")
    public List<AgenteInteraccionResponse> ejecutar(int limite) {
        if (limite < 1 || limite > 100) { throw new BusinessRuleException("El límite debe estar entre 1 y 100"); }
        boolean admin = SecurityContextHolder.getContext().getAuthentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return history.listarRecientes(admin ? null : actor.solicitante(), limite).stream().map(mapper::aResponse).toList();
    }
}
