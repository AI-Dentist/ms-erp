package com.dentalplatform.erp.financebilling.application.agent;

import com.dentalplatform.erp.financebilling.application.report.ReportResponse;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** GraphQL {@code AgenteInteraccionResponse}; {@code reporteGenerado} is the {@code ReporteGenerativoPayload}. */
public record AgenteInteraccionResponse(UUID interaccionId, OffsetDateTime fechaEjecucion, String instruccionOriginal,
                                        String respuestaNaturalAgente, List<String> toolsMcpInvocadas,
                                        String accionCrudEjecutada, boolean notificacionN8nEnviada,
                                        ReportResponse reporteGenerado) { }
