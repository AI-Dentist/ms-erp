package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.OrdenCobroItem;
import com.dentalplatform.erp.shared.domain.vo.Money;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "orden_cobro_items")
public class OrdenCobroItemJpaEntity {
    @Id UUID id;
    @Column(name = "orden_cobro_id", nullable = false) UUID ordenCobroId;
    @Column(name = "servicio_id", nullable = false) UUID servicioId;
    @Column(name = "pieza_dental", length = 10) String piezaDental;
    @Column(nullable = false) int cantidad;
    @Column(name = "precio_unitario_bs", precision = 12, scale = 2, nullable = false) BigDecimal precio;
    @Column(name = "subtotal_bs", precision = 12, scale = 2, nullable = false) BigDecimal subtotal;

    protected OrdenCobroItemJpaEntity() { }

    static OrdenCobroItemJpaEntity fromDomain(UUID order, OrdenCobroItem item) {
        var e = new OrdenCobroItemJpaEntity();
        e.id = item.id();
        e.ordenCobroId = order;
        e.servicioId = item.servicioId();
        e.piezaDental = item.piezaDental();
        e.cantidad = item.cantidad();
        e.precio = item.precioUnitario().amount();
        e.subtotal = item.subtotal().amount();
        return e;
    }

    OrdenCobroItem toDomain() {
        return new OrdenCobroItem(id, servicioId, piezaDental, cantidad, Money.of(precio), Money.of(subtotal));
    }
}
