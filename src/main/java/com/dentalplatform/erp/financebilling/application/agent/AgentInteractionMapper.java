package com.dentalplatform.erp.financebilling.application.agent;

import com.dentalplatform.erp.financebilling.application.report.ReportStore;
import com.dentalplatform.erp.financebilling.domain.model.AgenteInteraccion;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** The report payload is rebuilt from the owner-bound cache, so it disappears once the report expires (10 minutes). */
@Component
public class AgentInteractionMapper {
    private final ReportStore store;

    public AgentInteractionMapper(ReportStore store) { this.store = store; }

    public AgenteInteraccionResponse aResponse(AgenteInteraccion i) {
        var report = i.reporteId() == null ? null : store.find(
                SecurityContextHolder.getContext().getAuthentication().getName(), i.reporteId())
                .map(entry -> entry.response()).orElse(null);
        return new AgenteInteraccionResponse(i.id(), i.fecha(), i.instruccion(), i.respuesta(), i.tools(),
                i.accionCrud(), i.n8nEnviada(), report);
    }
}
