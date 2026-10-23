package com.dentalplatform.erp.financebilling.domain.model;

import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import com.dentalplatform.erp.shared.domain.vo.Money;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class OrdenCobro {
    private final UUID id;
    private final UUID pacienteId;
    private final UUID odontologoId;
    private final UUID consultaExternaId;
    private ModalidadPago modalidadPago;
    private final Money subtotal;
    private Money descuento;
    private Money total;
    private Money saldoPendiente;
    private EstadoOrdenCobro estado;
    private final String observaciones;
    private final OffsetDateTime createdAt;
    private final List<OrdenCobroItem> items;
    private List<CuotaPago> cuotas;

    public OrdenCobro(UUID id, UUID pacienteId, UUID odontologoId, UUID consultaExternaId,
                      ModalidadPago modalidadPago, Money subtotal, Money descuento, Money total, Money saldoPendiente,
                      EstadoOrdenCobro estado, String observaciones, OffsetDateTime createdAt,
                      List<OrdenCobroItem> items, List<CuotaPago> cuotas) {
        this.id = id;
        this.pacienteId = pacienteId;
        this.odontologoId = odontologoId;
        this.consultaExternaId = consultaExternaId;
        this.modalidadPago = modalidadPago;
        this.subtotal = subtotal;
        this.descuento = descuento;
        this.total = total;
        this.saldoPendiente = saldoPendiente;
        this.estado = estado;
        this.observaciones = observaciones;
        this.createdAt = createdAt;
        this.items = List.copyOf(items);
        this.cuotas = List.copyOf(cuotas);
    }

    public static OrdenCobro crear(UUID pacienteId, UUID odontologoId, UUID consultaId,
                                   List<OrdenCobroItem> items, String observaciones, OffsetDateTime now) {
        if (pacienteId == null || items == null || items.isEmpty()) {
            throw new BusinessRuleException("La orden requiere paciente e items");
        }
        Money subtotal = items.stream().map(OrdenCobroItem::subtotal).reduce(Money.zero(), Money::plus);
        var order = new OrdenCobro(UUID.randomUUID(), pacienteId, odontologoId, consultaId, ModalidadPago.CONTADO,
                subtotal, Money.zero(), subtotal, subtotal, EstadoOrdenCobro.PENDIENTE, observaciones, now,
                items, List.of());
        order.configurarPlanDePagos(ModalidadPago.CONTADO, Money.zero(), 1, now.toLocalDate(), 1);
        return order;
    }

    public void configurarPlanDePagos(ModalidadPago modalidad, Money descuento, int cantidadCuotas,
                                      LocalDate fechaPrimeraCuota, int intervaloDias) {
        if (estado != EstadoOrdenCobro.PENDIENTE || cuotas.stream().anyMatch(c -> c.estado() == EstadoCuota.PAGADA)) {
            throw new BusinessRuleException("No se puede modificar un plan con pagos o una orden anulada");
        }
        if (modalidad == null || descuento == null || fechaPrimeraCuota == null || intervaloDias < 1
                || cantidadCuotas < 1 || cantidadCuotas > 36
                || (modalidad == ModalidadPago.CONTADO && cantidadCuotas != 1)
                || (modalidad == ModalidadPago.CREDITO && cantidadCuotas < 2)) {
            throw new BusinessRuleException("Plan inválido: CONTADO requiere 1 cuota; CREDITO de 2 a 36; intervalo mínimo 1 día");
        }
        if (descuento.isGreaterThan(subtotal)) {
            throw new BusinessRuleException("El descuento no puede superar el subtotal");
        }
        Money newTotal = subtotal.minus(descuento);
        List<Money> parts = newTotal.splitInto(cantidadCuotas);
        List<CuotaPago> plan = new ArrayList<>();
        for (int i = 0; i < cantidadCuotas; i++) {
            plan.add(new CuotaPago(UUID.randomUUID(), i + 1, parts.get(i),
                    fechaPrimeraCuota.plusDays((long) i * intervaloDias), EstadoCuota.PENDIENTE, null));
        }
        this.modalidadPago = modalidad;
        this.descuento = descuento;
        this.total = newTotal;
        this.saldoPendiente = newTotal;
        this.cuotas = List.copyOf(plan);
    }

    /**
     * Logical annulment is deliberately non-idempotent: a repeat is an explicit business error, not another audit.
     * Historical totals/items/installment IDs remain intact; outstanding debt becomes zero because nothing is due.
     */
    public void anular(String motivo) {
        if (motivo == null || motivo.isBlank() || motivo.strip().length() > 500) {
            throw new BusinessRuleException("El motivo de anulación debe tener entre 1 y 500 caracteres");
        }
        if (estado == EstadoOrdenCobro.ANULADO) {
            throw new BusinessRuleException("La orden de cobro ya está anulada");
        }
        if (estado != EstadoOrdenCobro.PENDIENTE || cuotas.stream().anyMatch(c -> c.estado() == EstadoCuota.PAGADA)) {
            throw new BusinessRuleException("No se puede anular una orden con pagos registrados");
        }
        this.cuotas = cuotas.stream().map(CuotaPago::anular).toList();
        this.saldoPendiente = Money.zero();
        this.estado = EstadoOrdenCobro.ANULADO;
    }

    public CuotaPago registrarPagoCuota(UUID cuotaId, OffsetDateTime fechaPago) {
        if (estado == EstadoOrdenCobro.ANULADO) {
            throw new BusinessRuleException("No se puede cobrar una orden anulada");
        }
        CuotaPago cuota = cuotas.stream().filter(c -> c.id().equals(cuotaId)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("La cuota no pertenece a la orden"));
        CuotaPago paid = cuota.pagar(fechaPago);
        this.cuotas = cuotas.stream().map(c -> c.id().equals(cuotaId) ? paid : c).toList();
        this.saldoPendiente = saldoPendiente.minus(cuota.montoCuota());
        this.estado = saldoPendiente.isZero() ? EstadoOrdenCobro.PAGADO : EstadoOrdenCobro.PARCIAL;
        return paid;
    }

    public UUID getId() { return id; }
    public UUID getPacienteId() { return pacienteId; }
    public UUID getOdontologoId() { return odontologoId; }
    public UUID getConsultaExternaId() { return consultaExternaId; }
    public ModalidadPago getModalidadPago() { return modalidadPago; }
    public Money getSubtotal() { return subtotal; }
    public Money getDescuento() { return descuento; }
    public Money getTotal() { return total; }
    public Money getSaldoPendiente() { return saldoPendiente; }
    public EstadoOrdenCobro getEstado() { return estado; }
    public String getObservaciones() { return observaciones; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public List<OrdenCobroItem> getItems() { return items; }
    public List<CuotaPago> getCuotas() { return cuotas; }
    public int getCantidadCuotas() { return cuotas.size(); }
}
