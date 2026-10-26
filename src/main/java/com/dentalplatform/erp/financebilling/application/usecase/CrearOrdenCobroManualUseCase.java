package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.application.command.CrearOrdenCobroCommand;
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
public class CrearOrdenCobroManualUseCase {
    private final OrdenCobroRepository orders;
    private final ValidarReferenciasFinanzasService referencias;
    private final RegistrarAuditoriaUseCase auditoria;
    private final Clock clock;

    public CrearOrdenCobroManualUseCase(OrdenCobroRepository orders, ValidarReferenciasFinanzasService referencias,
                                       RegistrarAuditoriaUseCase auditoria, Clock clock) {
        this.orders = orders;
        this.referencias = referencias;
        this.auditoria = auditoria;
        this.clock = clock;
    }

    @Transactional
    public OrdenCobro ejecutar(CrearOrdenCobroCommand c) {
        referencias.pacienteYOdontologo(c.pacienteId(), c.odontologoId());
        if (c.items() == null || c.items().isEmpty()) {
            throw new BusinessRuleException("La orden requiere items");
        }
        var items = c.items().stream().map(i -> OrdenCobroItem.crear(i.servicioId(), i.piezaDental(), i.cantidad(),
                referencias.servicio(i.servicioId()).getPrecioBase())).toList();
        var now = OffsetDateTime.now(clock);
        var order = OrdenCobro.crear(c.pacienteId(), c.odontologoId(), null, items, c.observaciones(), now);
        order.configurarPlanDePagos(ModalidadPago.CONTADO, c.descuentoBs() == null ? Money.zero() : Money.of(c.descuentoBs()),
                1, now.toLocalDate(), 1);
        orders.guardar(order);
        auditoria.registrar("ORDEN_COBRO_CREADA", "ordenes_cobro", order.getId(), Map.of());
        return order;
    }
}
