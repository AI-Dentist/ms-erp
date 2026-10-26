package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.*;
import com.dentalplatform.erp.financebilling.domain.repository.OrdenCobroRepository;
import com.dentalplatform.erp.shared.domain.vo.Money;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
class OrdenCobroRepositoryAdapter implements OrdenCobroRepository {
    private final OrdenCobroJpaRepository orders;
    private final OrdenCobroItemJpaRepository items;
    private final CuotaPagoJpaRepository cuotas;
    private final EntityManager entityManager;

    OrdenCobroRepositoryAdapter(OrdenCobroJpaRepository orders, OrdenCobroItemJpaRepository items,
                                CuotaPagoJpaRepository cuotas, EntityManager entityManager) {
        this.orders = orders;
        this.items = items;
        this.cuotas = cuotas;
        this.entityManager = entityManager;
    }

    @Override
    public OrdenCobro guardar(OrdenCobro order) {
        orders.saveAndFlush(OrdenCobroJpaEntity.fromDomain(order));
        items.saveAll(order.getItems().stream().map(i -> OrdenCobroItemJpaEntity.fromDomain(order.getId(), i)).toList());
        var retainedIds = order.getCuotas().stream().map(CuotaPago::id).toList();
        var removed = cuotas.findByOrdenCobroIdOrderByNumeroCuota(order.getId()).stream()
                .filter(c -> !retainedIds.contains(c.id)).toList();
        cuotas.deleteAll(removed);
        // Delete the replaced plan before inserting cuotas with the same (order, installment number).
        cuotas.flush();
        cuotas.saveAllAndFlush(order.getCuotas().stream().map(c -> CuotaPagoJpaEntity.fromDomain(order.getId(), c)).toList());
        return order;
    }

    @Override
    public Optional<OrdenCobro> buscarPorId(UUID id) { return orders.findById(id).map(this::toDomain); }

    @Override
    public Optional<OrdenCobro> bloquearPorId(UUID id) { return orders.bloquear(id).map(this::toDomain); }

    @Override
    public Optional<OrdenCobro> bloquearPorCuotaId(UUID cuotaId) {
        return cuotas.obtenerOrdenId(cuotaId).flatMap(this::bloquearPorId);
    }

    @Override
    public boolean existeConsulta(UUID consultaId) { return orders.existsByConsultaExternaId(consultaId); }

    @Override
    public void bloquearConsulta(UUID consultaId) {
        // Transaction-scoped advisory lock serializes duplicate deliveries even before the order exists.
        entityManager.createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                .setParameter("key", consultaId.getMostSignificantBits() ^ consultaId.getLeastSignificantBits())
                .getSingleResult();
    }

    @Override
    public List<OrdenCobro> listar(EstadoOrdenCobro estado, UUID pacienteId) {
        return orders.listar(estado, pacienteId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<CuotaPago> cuotasPendientes(UUID pacienteId) {
        return cuotas.pendientes(pacienteId).stream().map(CuotaPagoJpaEntity::toDomain).toList();
    }

    private OrdenCobro toDomain(OrdenCobroJpaEntity e) {
        return new OrdenCobro(e.id, e.pacienteId, e.odontologoId, e.consultaExternaId, e.modalidadPago,
                Money.of(e.subtotal), Money.of(e.descuento), Money.of(e.total), Money.of(e.saldo), e.estado,
                e.observaciones, e.createdAt,
                items.findByOrdenCobroIdOrderById(e.id).stream().map(OrdenCobroItemJpaEntity::toDomain).toList(),
                cuotas.findByOrdenCobroIdOrderByNumeroCuota(e.id).stream().map(CuotaPagoJpaEntity::toDomain).toList());
    }
}
