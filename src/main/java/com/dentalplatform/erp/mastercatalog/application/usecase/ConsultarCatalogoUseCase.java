package com.dentalplatform.erp.mastercatalog.application.usecase;

import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;
import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;
import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;
import com.dentalplatform.erp.mastercatalog.domain.repository.ConsultorioRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.EspecialidadRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.OdontologoRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.PacienteRepository;
import com.dentalplatform.erp.mastercatalog.domain.repository.ServicioOdontologicoRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only queries over the master catalogs.
 */
@Service
@Transactional(readOnly = true)
public class ConsultarCatalogoUseCase {

    private final PacienteRepository pacientes;
    private final OdontologoRepository odontologos;
    private final EspecialidadRepository especialidades;
    private final ConsultorioRepository consultorios;
    private final ServicioOdontologicoRepository servicios;

    public ConsultarCatalogoUseCase(PacienteRepository pacientes, OdontologoRepository odontologos,
                                    EspecialidadRepository especialidades, ConsultorioRepository consultorios,
                                    ServicioOdontologicoRepository servicios) {
        this.pacientes = pacientes;
        this.odontologos = odontologos;
        this.especialidades = especialidades;
        this.consultorios = consultorios;
        this.servicios = servicios;
    }

    public List<Paciente> listarPacientes(String busqueda) {
        return pacientes.buscarActivos(busqueda);
    }

    public Optional<Paciente> obtenerPaciente(UUID id) {
        return pacientes.buscarPorId(id);
    }

    public List<Odontologo> listarOdontologos(boolean soloActivos) {
        return odontologos.listar(soloActivos);
    }

    public List<Especialidad> listarEspecialidades() {
        return especialidades.listarActivas();
    }

    public List<Consultorio> listarConsultorios() {
        return consultorios.listar();
    }

    public List<ServicioOdontologico> listarServicios(UUID especialidadId) {
        return servicios.listarActivos(especialidadId);
    }
}
