package com.dentalplatform.erp.financebilling.infrastructure.persistence;

import com.dentalplatform.erp.financebilling.domain.model.Recibo;
import com.dentalplatform.erp.financebilling.domain.repository.ReciboRepository;
import org.springframework.stereotype.Component;
import java.util.Optional;
import java.util.UUID;

@Component
public class ReciboRepositoryAdapter implements ReciboRepository {
    private final ReciboJpaRepository repository;

    ReciboRepositoryAdapter(ReciboJpaRepository repository) { this.repository = repository; }

    @Override
    public long siguienteNumero() { return repository.siguienteNumero(); }

    @Override
    public Recibo guardar(Recibo recibo) {
        return repository.saveAndFlush(ReciboJpaEntity.fromDomain(recibo)).toDomain();
    }

    @Override
    public Optional<Recibo> buscarPorCuota(UUID cuotaId) {
        return repository.buscarPorCuota(cuotaId).map(ReciboJpaEntity::toDomain);
    }

    @Override
    public Optional<Recibo> buscarPorMovimiento(UUID movimientoId) {
        return repository.findByMovimientoCajaId(movimientoId).map(ReciboJpaEntity::toDomain);
    }
}
