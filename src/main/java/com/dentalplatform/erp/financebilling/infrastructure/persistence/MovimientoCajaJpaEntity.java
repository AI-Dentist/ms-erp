package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.shared.domain.vo.Money;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "movimientos_caja")
public class MovimientoCajaJpaEntity {
    @Id UUID id;
    @Column(name = "cuota_id", unique = true) UUID cuotaId;
    @Column(name = "usuario_cajero_id", nullable = false) UUID usuarioCajeroId;
    @Enumerated(EnumType.STRING) @Column(name = "tipo_movimiento", length = 20, nullable = false) TipoMovimientoCaja tipo;
    @Column(length = 50, nullable = false) String categoria;
    @Enumerated(EnumType.STRING) @Column(name = "metodo_pago", length = 30, nullable = false) MetodoPago metodo;
    @Column(name = "monto_bs", precision = 12, scale = 2, nullable = false) BigDecimal monto;
    @Column(nullable = false) String descripcion;
    @Column(name = "fecha_movimiento") OffsetDateTime fecha;

    protected MovimientoCajaJpaEntity() { }

    static MovimientoCajaJpaEntity fromDomain(MovimientoCaja m) {
        var e = new MovimientoCajaJpaEntity();
        e.id = m.id();
        e.cuotaId = m.cuotaId();
        e.usuarioCajeroId = m.usuarioCajeroId();
        e.tipo = m.tipoMovimiento();
        e.categoria = m.categoria();
        e.metodo = m.metodoPago();
        e.monto = m.monto().amount();
        e.descripcion = m.descripcion();
        e.fecha = m.fechaMovimiento();
        return e;
    }

    MovimientoCaja toDomain() {
        return new MovimientoCaja(id, cuotaId, usuarioCajeroId, tipo, categoria, metodo, Money.of(monto), descripcion, fecha);
    }
}
