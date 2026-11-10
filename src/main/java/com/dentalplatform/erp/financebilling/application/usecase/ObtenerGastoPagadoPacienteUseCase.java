package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.port.GastoPagadoReadPort;
import com.dentalplatform.erp.shared.domain.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Service
public class ObtenerGastoPagadoPacienteUseCase {
    private final GastoPagadoReadPort read;

    public ObtenerGastoPagadoPacienteUseCase(GastoPagadoReadPort read) { this.read = read; }

    public record Resultado(UUID pacienteId, String moneda, boolean completo, BigDecimal totalPagadoBs) { }

    @Transactional(readOnly = true)
    public Resultado ejecutar(UUID pacienteId) {
        var rows = read.consultar(pacienteId);
        if (rows.isEmpty()) throw new ResourceNotFoundException("El paciente no existe");
        BigDecimal total = BigDecimal.ZERO;
        Set<UUID> cuotas = new HashSet<>(), movimientos = new HashSet<>(), recibos = new HashSet<>();
        var orderStates = new HashMap<UUID, String>();
        var paidOrders = new HashSet<UUID>();
        var unpaidOrders = new HashSet<UUID>();
        for (var row : rows) {
            if (row.ordenId() == null) {
                if (rows.size() != 1 || row.cuotaId() != null || row.movimientoId() != null || row.reciboId() != null
                        || row.estadoOrden() != null || row.estadoCuota() != null || row.fechaPagoPresente()
                        || row.montoCuota() != null) return incompleto(pacienteId);
                continue;
            }
            if (!row.planConsistente() || row.estadoOrden() == null || !Set.of("PENDIENTE", "PARCIAL", "PAGADO", "ANULADO").contains(row.estadoOrden())
                    || row.cuotaId() == null || !cuotas.add(row.cuotaId())
                    || row.estadoCuota() == null
                    || !Set.of("PENDIENTE", "PAGADA", "VENCIDA", "ANULADA").contains(row.estadoCuota())
                    || row.montoCuota() == null || row.montoCuota().signum() <= 0) {
                return incompleto(pacienteId);
            }
            String priorState = orderStates.putIfAbsent(row.ordenId(), row.estadoOrden());
            if (priorState != null && !priorState.equals(row.estadoOrden())) return incompleto(pacienteId);
            if (!"PAGADA".equals(row.estadoCuota())) {
                if (row.movimientoId() != null || row.reciboId() != null || row.fechaPagoPresente()
                        || "PAGADO".equals(row.estadoOrden())
                        || ("ANULADO".equals(row.estadoOrden()) != "ANULADA".equals(row.estadoCuota()))) return incompleto(pacienteId);
                unpaidOrders.add(row.ordenId());
                continue;
            }
            paidOrders.add(row.ordenId());
            if ("ANULADO".equals(row.estadoOrden()) || "PENDIENTE".equals(row.estadoOrden())
                    || row.movimientoId() == null || !movimientos.add(row.movimientoId())
                    || row.reciboId() == null || !recibos.add(row.reciboId())
                    || !"INGRESO".equals(row.tipo()) || !"COBRO_CUOTA".equals(row.categoria())
                    || !"EFECTIVO".equals(row.metodo()) || !row.fechasCoinciden()
                    || !row.fechaPagoPresente()
                    || row.montoMovimiento() == null || row.montoRecibo() == null
                    || row.montoCuota().compareTo(row.montoMovimiento()) != 0
                    || row.montoCuota().compareTo(row.montoRecibo()) != 0) return incompleto(pacienteId);
            total = total.add(row.montoCuota());
        }
        for (var order : orderStates.entrySet()) {
            if ("PARCIAL".equals(order.getValue())
                    && (!paidOrders.contains(order.getKey()) || !unpaidOrders.contains(order.getKey()))) return incompleto(pacienteId);
        }
        return new Resultado(pacienteId, "BOB", true, total);
    }

    private Resultado incompleto(UUID pacienteId) { return new Resultado(pacienteId, "BOB", false, null); }
}
