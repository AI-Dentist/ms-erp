package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.financebilling.domain.repository.*;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ConsultarFinanzasUseCase {
    private final OrdenCobroRepository orders;
    private final MovimientoCajaRepository movements;
    private final ReciboRepository receipts;

    public ConsultarFinanzasUseCase(OrdenCobroRepository orders, MovimientoCajaRepository movements, ReciboRepository receipts) {
        this.orders = orders;
        this.movements = movements;
        this.receipts = receipts;
    }

    public List<OrdenCobro> listarOrdenesCobro(EstadoOrdenCobro estado, UUID pacienteId) { return orders.listar(estado, pacienteId); }
    public Optional<OrdenCobro> obtenerOrdenCobroDetalle(UUID id) { return orders.buscarPorId(id); }
    public List<CuotaPago> listarCuotasPendientesPorPaciente(UUID pacienteId) { return orders.cuotasPendientes(pacienteId); }
    public Optional<Recibo> obtenerRecibo(UUID movimientoId) { return receipts.buscarPorMovimiento(movimientoId); }

    public ResumenFlujoCaja obtenerFlujoCaja(OffsetDateTime inicio, OffsetDateTime fin) {
        if (inicio == null || fin == null || inicio.isAfter(fin)) {
            throw new BusinessRuleException("El rango de fechas de caja es inválido");
        }
        var rows = movements.listar(inicio, fin);
        Money income = Money.zero(), expenses = Money.zero();
        for (var movement : rows) {
            if (movement.tipoMovimiento() == TipoMovimientoCaja.INGRESO) { income = income.plus(movement.monto()); }
            else { expenses = expenses.plus(movement.monto()); }
        }
        return new ResumenFlujoCaja(income.amount(), expenses.amount(), income.amount().subtract(expenses.amount()), rows);
    }

    // Query DTO: the net balance is signed, unlike domain Money amounts.
    public record ResumenFlujoCaja(BigDecimal totalIngresosBs, BigDecimal totalEgresosBs, BigDecimal saldoNetoBs,
                                   List<MovimientoCaja> movimientos) { }
}
