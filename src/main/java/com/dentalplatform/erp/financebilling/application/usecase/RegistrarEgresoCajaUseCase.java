package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.application.command.RegistrarEgresoCommand;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.financebilling.domain.repository.MovimientoCajaRepository;
import com.dentalplatform.erp.shared.domain.vo.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class RegistrarEgresoCajaUseCase {
    private final MovimientoCajaRepository movements;
    private final ValidarReferenciasFinanzasService referencias;
    private final RegistrarAuditoriaUseCase auditoria;
    private final Clock clock;

    public RegistrarEgresoCajaUseCase(MovimientoCajaRepository movements, ValidarReferenciasFinanzasService referencias,
                                      RegistrarAuditoriaUseCase auditoria, Clock clock) {
        this.movements = movements;
        this.referencias = referencias;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    @Transactional
    public MovimientoCaja ejecutar(RegistrarEgresoCommand c) {
        referencias.cajero(c.usuarioCajeroId());
        var movement = MovimientoCaja.crear(null, c.usuarioCajeroId(), TipoMovimientoCaja.EGRESO, c.categoria(),
                c.metodoPago(), Money.of(c.montoBs()), c.descripcion(), OffsetDateTime.now(clock));
        movements.guardar(movement);
        auditoria.registrar("EGRESO_CAJA_REGISTRADO", "movimientos_caja", movement.id(), Map.of());
        return movement;
    }
}
