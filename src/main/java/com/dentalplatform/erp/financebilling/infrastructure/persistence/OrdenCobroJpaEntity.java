package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.*;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "ordenes_cobro")
public class OrdenCobroJpaEntity {
    @Id UUID id;
    @Column(name = "paciente_id", nullable = false) UUID pacienteId;
    @Column(name = "odontologo_id") UUID odontologoId;
    @Column(name = "consulta_externa_id", unique = true) UUID consultaExternaId;
    @Enumerated(EnumType.STRING) @Column(name = "modalidad_pago", length = 20, nullable = false)
    ModalidadPago modalidadPago;
    @Column(name = "monto_subtotal_bs", precision = 12, scale = 2, nullable = false) BigDecimal subtotal;
    @Column(name = "descuento_bs", precision = 12, scale = 2) BigDecimal descuento;
    @Column(name = "monto_total_bs", precision = 12, scale = 2, nullable = false) BigDecimal total;
    @Column(name = "saldo_pendiente_bs", precision = 12, scale = 2, nullable = false) BigDecimal saldo;
    @Column(name = "cantidad_cuotas", nullable = false) int cantidadCuotas;
    @Enumerated(EnumType.STRING) @Column(length = 30, nullable = false) EstadoOrdenCobro estado;
    String observaciones;
    @Column(name = "created_at") OffsetDateTime createdAt;

    protected OrdenCobroJpaEntity() { }

    static OrdenCobroJpaEntity fromDomain(OrdenCobro o) {
        var e = new OrdenCobroJpaEntity();
        e.id = o.getId();
        e.pacienteId = o.getPacienteId();
        e.odontologoId = o.getOdontologoId();
        e.consultaExternaId = o.getConsultaExternaId();
        e.modalidadPago = o.getModalidadPago();
        e.subtotal = o.getSubtotal().amount();
        e.descuento = o.getDescuento().amount();
        e.total = o.getTotal().amount();
        e.saldo = o.getSaldoPendiente().amount();
        e.cantidadCuotas = o.getCantidadCuotas();
        e.estado = o.getEstado();
        e.observaciones = o.getObservaciones();
        e.createdAt = o.getCreatedAt();
        return e;
    }
}
