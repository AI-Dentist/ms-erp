package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.application.command.ConfigurarPlanPagosCommand;
import com.dentalplatform.erp.financebilling.domain.model.OrdenCobro;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;

@Service
public class ConfigurarPlanPagosUseCase {
    private final OrdenCobroRepository orders;
    private final RegistrarAuditoriaUseCase auditoria;

    public ConfigurarPlanPagosUseCase(OrdenCobroRepository orders, RegistrarAuditoriaUseCase auditoria) {
        this.orders = orders;
        this.auditoria = auditoria;
    }

    @Transactional
    public OrdenCobro ejecutar(ConfigurarPlanPagosCommand c) {
        var order = orders.bloquearPorId(c.ordenCobroId())
                .orElseThrow(() -> new ResourceNotFoundException("La orden de cobro no existe"));
        order.configurarPlanDePagos(c.modalidadPago(), c.descuentoBs() == null ? Money.zero() : Money.of(c.descuentoBs()),
                c.cantidadCuotas(), c.fechaPrimeraCuota(), c.intervaloDias());
        orders.guardar(order);
        auditoria.registrar("PLAN_PAGOS_CONFIGURADO", "ordenes_cobro", order.getId(), Map.of());
        return order;
    }
}
