package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.domain.model.OrdenCobro;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Map;
import java.util.UUID;

/** Financial cancellation uses the same cashier roles and ORDER lock as payment/QR. */
@Service
public class AnularOrdenCobroUseCase {
    private final OrdenCobroRepository orders;
    private final RegistrarAuditoriaUseCase auditoria;

    public AnularOrdenCobroUseCase(OrdenCobroRepository orders, RegistrarAuditoriaUseCase auditoria) {
        this.orders = orders;
        this.auditoria = auditoria;
    }

    @PreAuthorize("hasAnyRole('ADMIN','CAJERO')")
    @Transactional
    public OrdenCobro ejecutar(UUID ordenId, String motivo) {
        var order = orders.bloquearPorId(ordenId)
                .orElseThrow(() -> new ResourceNotFoundException("La orden de cobro no existe"));
        order.anular(motivo);
        orders.guardar(order);
        auditoria.registrar("ORDEN_COBRO_ANULADA", "ordenes_cobro", order.getId(), Map.of("motivo", motivo.strip()));
        return order;
    }
}
