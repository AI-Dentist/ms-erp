package com.dentalplatform.erp.financebilling.application.port;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public interface GastoPagadoReadPort {
    List<Evidencia> consultar(UUID pacienteId);

    record Evidencia(UUID ordenId, String estadoOrden, boolean planConsistente, UUID cuotaId, String estadoCuota,
                     BigDecimal montoCuota, boolean fechaPagoPresente, UUID movimientoId,
                     String tipo, String categoria, String metodo, BigDecimal montoMovimiento,
                     UUID reciboId, BigDecimal montoRecibo, boolean fechasCoinciden) { }
}
