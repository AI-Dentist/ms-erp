package com.dentalplatform.erp.mastercatalog.infrastructure.persistence;

import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;
import com.dentalplatform.erp.mastercatalog.domain.repository.PacienteRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Component
class PacienteRepositoryAdapter implements PacienteRepository {

    private final PacienteJpaRepository repository;

    PacienteRepositoryAdapter(PacienteJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public List<Paciente> buscarActivos(String busqueda) {
        List<PacienteJpaEntity> found = busqueda == null || busqueda.isBlank()
                ? repository.findByActivoTrueOrderByApellidosAscNombresAsc()
                : repository.buscarActivos("%" + busqueda.trim().toLowerCase(Locale.ROOT) + "%");
        return found.stream().map(PacienteRepositoryAdapter::toDomain).toList();
    }

    @Override
    public Optional<Paciente> buscarPorId(UUID id) {
        return repository.findById(id).map(PacienteRepositoryAdapter::toDomain);
    }

    @Override
    public boolean existeCiNit(String ciNit) {
        return repository.existsByCiNit(ciNit);
    }

    @Override
    public Paciente guardar(Paciente p) {
        return toDomain(repository.save(new PacienteJpaEntity(p.getId(), p.getCiNit(), p.getNombres(),
                p.getApellidos(), p.getFechaNacimiento(), p.getGenero(), p.getTelefonoWhatsapp(), p.getEmail(),
                p.getDireccion(), p.isActivo(), p.getCreatedAt())));
    }

    private static Paciente toDomain(PacienteJpaEntity e) {
        return new Paciente(e.getId(), e.getCiNit(), e.getNombres(), e.getApellidos(), e.getFechaNacimiento(),
                e.getGenero(), e.getTelefonoWhatsapp(), e.getEmail(), e.getDireccion(), e.isActivo(),
                e.getCreatedAt());
    }
}
