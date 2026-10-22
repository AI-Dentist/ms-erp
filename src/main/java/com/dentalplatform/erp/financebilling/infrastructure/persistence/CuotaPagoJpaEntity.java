package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.CuotaPago;
import com.dentalplatform.erp.financebilling.domain.model.EstadoCuota;
import com.dentalplatform.erp.shared.domain.vo.Money;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "cuotas_pago", uniqueConstraints = @UniqueConstraint(columnNames = {"orden_cobro_id", "numero_cuota"}))
public class CuotaPagoJpaEntity {
    @Id UUID id;
    @Column(name = "orden_cobro_id", nullable = false) UUID ordenCobroId;
    @Column(name = "numero_cuota", nullable = false) int numeroCuota;
    @Column(name = "monto_cuota_bs", precision = 12, scale = 2, nullable = false) BigDecimal monto;
    @Column(name = "fecha_vencimiento", nullable = false) LocalDate fechaVencimiento;
    @Enumerated(EnumType.STRING) @Column(length = 30, nullable = false) EstadoCuota estado;
    @Column(name = "fecha_pago") OffsetDateTime fechaPago;

    protected CuotaPagoJpaEntity() { }

    static CuotaPagoJpaEntity fromDomain(UUID order, CuotaPago cuota) {
        var e = new CuotaPagoJpaEntity();
        e.id = cuota.id();
        e.ordenCobroId = order;
        e.numeroCuota = cuota.numeroCuota();
        e.monto = cuota.montoCuota().amount();
        e.fechaVencimiento = cuota.fechaVencimiento();
        e.estado = cuota.estado();
        e.fechaPago = cuota.fechaPago();
        return e;
    }

    CuotaPago toDomain() {
        return new CuotaPago(id, numeroCuota, Money.of(monto), fechaVencimiento, estado, fechaPago);
    }
}
