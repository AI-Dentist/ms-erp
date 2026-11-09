package com.dentalplatform.erp.financebilling.application.agent;

import java.util.List;

/** A per-invocation result: simulation is explicit and never implies delivery through a real provider. */
public record NotificacionMulticanalPayload(boolean exito, String modoEjecucion,
                                           List<ResultadoCanalNotificacion> resultadosPorCanal) {
    public NotificacionMulticanalPayload { resultadosPorCanal = List.copyOf(resultadosPorCanal); }
    public record ResultadoCanalNotificacion(String canal, String proveedor, String estado, String destino) { }
}
