package com.dentalplatform.erp.financebilling.application.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Authorized DDD monitoring projection, NOT part of the generative SQL reporting allowlist. Contains PII. */
public interface MonitoreoNegocioPort {
    List<CuotaVencida> cuotasVencidas(LocalDate fechaCorte);

    record CuotaVencida(UUID pacienteId, String nombreCompleto, String ciNit, String telefonoWhatsapp,
                       UUID ordenId, UUID cuotaId, int numeroCuota, BigDecimal montoBs,
                       LocalDate vencimiento, long diasAtraso) { }
}
