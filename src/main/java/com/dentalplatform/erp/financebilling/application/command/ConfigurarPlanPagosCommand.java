package com.dentalplatform.erp.financebilling.application.command;

import com.dentalplatform.erp.financebilling.domain.model.ModalidadPago;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record ConfigurarPlanPagosCommand(UUID ordenCobroId, ModalidadPago modalidadPago, BigDecimal descuentoBs,
                                        int cantidadCuotas, LocalDate fechaPrimeraCuota, int intervaloDias) { }
