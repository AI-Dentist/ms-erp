package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Resolves the specialty ids a dentist is assigned, rejecting any that does not exist. */
final class EspecialidadesAsignadas {

    private EspecialidadesAsignadas() {
    }

    static List<Especialidad> resolver(EspecialidadRepository especialidades, List<UUID> ids) {
        Set<UUID> unicos = ids == null ? Set.of() : new LinkedHashSet<>(ids);
        List<Especialidad> encontradas = especialidades.buscarPorIds(unicos);
        Set<UUID> encontradasIds = new LinkedHashSet<>();
        encontradas.forEach(e -> encontradasIds.add(e.getId()));
        List<UUID> faltantes = unicos.stream().filter(id -> !encontradasIds.contains(id)).toList();
        if (!faltantes.isEmpty()) {
            throw new BusinessRuleException("No existen las especialidades: " + faltantes);
        }
        return encontradas;
    }
}
