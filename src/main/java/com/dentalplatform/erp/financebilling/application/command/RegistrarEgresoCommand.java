package com.dentalplatform.erp.financebilling.application.command;

import com.dentalplatform.erp.financebilling.domain.model.MetodoPago;
import java.math.BigDecimal;
import java.util.UUID;

public record RegistrarEgresoCommand(UUID usuarioCajeroId, String categoria, MetodoPago metodoPago,
                                    BigDecimal montoBs, String descripcion) { }
