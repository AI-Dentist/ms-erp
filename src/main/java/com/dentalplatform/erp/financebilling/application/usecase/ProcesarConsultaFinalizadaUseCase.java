package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.application.command.ConsultaFinalizadaCommand;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

@Service
public class ProcesarConsultaFinalizadaUseCase {
    private final OrdenCobroRepository orders;
    private final ValidarReferenciasFinanzasService referencias;
    private final RegistrarAuditoriaUseCase auditoria;
    private final Clock clock;

    public ProcesarConsultaFinalizadaUseCase(OrdenCobroRepository orders, ValidarReferenciasFinanzasService referencias,
                                            RegistrarAuditoriaUseCase auditoria, Clock clock) {
        this.orders = orders;
        this.referencias = referencias;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    @Transactional
    public void ejecutar(ConsultaFinalizadaCommand c) {
        if (c.consultaId() == null) { throw new BusinessRuleException("El consultaId es obligatorio"); }
        orders.bloquearConsulta(c.consultaId());
        if (orders.existeConsulta(c.consultaId())) { return; }
        referencias.pacienteYOdontologo(c.pacienteId(), c.odontologoId());
        if (c.serviciosRealizados() == null || c.serviciosRealizados().isEmpty()) {
            throw new BusinessRuleException("La consulta requiere servicios realizados");
        }
        var items = c.serviciosRealizados().stream().map(i -> {
            referencias.servicio(i.servicioId());
            return OrdenCobroItem.crear(i.servicioId(), i.piezaDental(), i.cantidad(), Money.of(i.precioUnitarioBs()));
        }).toList();
        var order = OrdenCobro.crear(c.pacienteId(), c.odontologoId(), c.consultaId(), items,
                c.observacionesCobro(), OffsetDateTime.now(clock));
        orders.guardar(order);
        auditoria.registrar("ORDEN_COBRO_CREADA", "ordenes_cobro", order.getId(), Map.of("consultaId", c.consultaId()));
    }
}
