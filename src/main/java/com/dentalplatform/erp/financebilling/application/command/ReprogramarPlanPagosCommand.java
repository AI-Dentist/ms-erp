package com.dentalplatform.erp.financebilling.application.command;

import com.dentalplatform.erp.financebilling.domain.model.ModalidadPago;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** Null means preserve the current value; descuentoBs = 0 explicitly removes the discount. */
public record ReprogramarPlanPagosCommand(UUID ordenCobroId, ModalidadPago modalidadPago, BigDecimal descuentoBs,
                                          Integer cantidadCuotas, LocalDate fechaPrimeraCuota, Integer intervaloDias) { }
