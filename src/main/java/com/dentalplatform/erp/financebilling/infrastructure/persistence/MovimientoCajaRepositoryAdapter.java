package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.MovimientoCaja;
import com.dentalplatform.erp.financebilling.domain.repository.MovimientoCajaRepository;
import org.springframework.stereotype.Component;
import java.time.OffsetDateTime;
import java.util.List;

@Component
class MovimientoCajaRepositoryAdapter implements MovimientoCajaRepository {
    private final MovimientoCajaJpaRepository repository;

    MovimientoCajaRepositoryAdapter(MovimientoCajaJpaRepository repository) { this.repository = repository; }

    @Override
    public MovimientoCaja guardar(MovimientoCaja movimiento) {
        return repository.saveAndFlush(MovimientoCajaJpaEntity.fromDomain(movimiento)).toDomain();
    }

    @Override
    public List<MovimientoCaja> listar(OffsetDateTime inicio, OffsetDateTime fin) {
        return repository.findByFechaBetweenOrderByFechaAscIdAsc(inicio, fin).stream()
                .map(MovimientoCajaJpaEntity::toDomain).toList();
    }
}
