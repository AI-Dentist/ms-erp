package com.dentalplatform.erp.financebilling.application.usecase;

import com.dentalplatform.erp.configsecurity.domain.repository.UsuarioRepository;
import com.dentalplatform.erp.mastercatalog.application.usecase.ConsultarCatalogoUseCase;
import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;
import com.dentalplatform.erp.shared.domain.exception.BusinessRuleException;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class ValidarReferenciasFinanzasService {
    private final ConsultarCatalogoUseCase catalogo;
    private final UsuarioRepository usuarios;

    public ValidarReferenciasFinanzasService(ConsultarCatalogoUseCase catalogo, UsuarioRepository usuarios) {
        this.catalogo = catalogo;
        this.usuarios = usuarios;
    }

    public void pacienteYOdontologo(UUID pacienteId, UUID odontologoId) {
        if (pacienteId == null || catalogo.obtenerPaciente(pacienteId).isEmpty()) {
            throw new BusinessRuleException("El paciente no existe: " + pacienteId);
        }
        if (odontologoId != null && catalogo.listarOdontologos(false).stream().noneMatch(o -> o.getId().equals(odontologoId))) {
            throw new BusinessRuleException("El odontólogo no existe: " + odontologoId);
        }
    }

    public ServicioOdontologico servicio(UUID servicioId) {
        return catalogo.listarServicios(null).stream().filter(s -> s.getId().equals(servicioId)).findFirst()
                .orElseThrow(() -> new BusinessRuleException("El servicio no existe o está inactivo: " + servicioId));
    }

    public void cajero(UUID id) {
        if (id == null || usuarios.buscarPorId(id).isEmpty()) {
            throw new BusinessRuleException("El usuario cajero no existe: " + id);
        }
    }
}
