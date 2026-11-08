package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.application.usecase.RegistrarAuditoriaUseCase;
import com.dentalplatform.erp.financebilling.domain.model.CuotaPago;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Service
public class ReprogramarCuotaPlanPagoUseCase {
    private final OrdenCobroRepository orders;
    private final RegistrarAuditoriaUseCase audit;
    public ReprogramarCuotaPlanPagoUseCase(OrdenCobroRepository orders, RegistrarAuditoriaUseCase audit) {
        this.orders = orders;
        this.audit = audit;
    }
    @Transactional
    @PreAuthorize("hasAnyRole('ADMIN','CAJERO','RECEPCIONISTA')")
    public CuotaPago ejecutar(UUID cuotaId, LocalDate fecha) {
        var order = orders.bloquearPorCuotaId(cuotaId)
                .orElseThrow(() -> new ResourceNotFoundException("La cuota no existe"));
        var changed = order.reprogramarCuota(cuotaId, fecha);
        orders.guardar(order);
        audit.registrar("CUOTA_REPROGRAMADA", "cuotas_pago", cuotaId, Map.of("fechaVencimiento", fecha.toString()));
        return changed;
    }
}
