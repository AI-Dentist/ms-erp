package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.financebilling.application.port.MonitoreoNegocioPort;
import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.financebilling.domain.repository.MovimientoCajaRepository;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Caller-authorized monitoring; never changes overdue statuses and never executes generated SQL. */
@Service
@PreAuthorize("hasAnyRole('ADMIN','CAJERO')")
@Transactional(readOnly = true)
public class MonitoreoNegocioUseCase {
    private final MovimientoCajaRepository movements;
    private final OrdenCobroRepository orders;
    private final MonitoreoNegocioPort monitoring;

    public MonitoreoNegocioUseCase(MovimientoCajaRepository movements, OrdenCobroRepository orders,
                                  MonitoreoNegocioPort monitoring) {
        this.movements = movements;
        this.orders = orders;
        this.monitoring = monitoring;
    }

    /** Cash range is inclusive at both ends, matching ConsultarFinanzas; debt is the current outstanding stock. */
    public ResumenFinanciero resumenFinanciero(OffsetDateTime desde, OffsetDateTime hasta) {
        if (desde == null || hasta == null || desde.isAfter(hasta)) {
            throw new BusinessRuleException("El rango de fechas de caja es inválido");
        }
        var rows = movements.listar(desde, hasta);
        List<FlujoMetodo> methods = new ArrayList<>();
        for (MetodoPago method : MetodoPago.values()) {
            BigDecimal income = BigDecimal.ZERO.setScale(2), expense = BigDecimal.ZERO.setScale(2);
            for (var row : rows) {
                if (row.metodoPago() != method) { continue; }
                if (row.tipoMovimiento() == TipoMovimientoCaja.INGRESO) { income = income.add(row.monto().amount()); }
                else { expense = expense.add(row.monto().amount()); }
            }
            methods.add(new FlujoMetodo(method, income, expense, income.subtract(expense)));
        }
        var pending = orders.listar(EstadoOrdenCobro.PENDIENTE, null);
        var partial = orders.listar(EstadoOrdenCobro.PARCIAL, null);
        BigDecimal balance = java.util.stream.Stream.concat(pending.stream(), partial.stream())
                .map(o -> o.getSaldoPendiente().amount()).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
        return new ResumenFinanciero(List.copyOf(methods), pending.size(), partial.size(), balance);
    }

    public List<MonitoreoNegocioPort.CuotaVencida> cuotasVencidas(LocalDate fechaCorte) {
        if (fechaCorte == null) { throw new BusinessRuleException("La fecha de corte es obligatoria"); }
        return monitoring.cuotasVencidas(fechaCorte);
    }

    public record FlujoMetodo(MetodoPago metodoPago, BigDecimal ingresosBs, BigDecimal egresosBs, BigDecimal saldoNetoBs) { }
    public record ResumenFinanciero(List<FlujoMetodo> porMetodoPago, long ordenesPendientes,
                                    long ordenesParciales, BigDecimal saldoPendienteTotalBs) { }
}
