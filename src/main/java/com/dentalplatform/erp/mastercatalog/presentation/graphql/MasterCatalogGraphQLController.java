package com.dentalplatform.erp.mastercatalog.presentation.graphql;

import com.dentalplatform.erp.mastercatalog.application.command.CrearEspecialidadCommand;
import com.dentalplatform.erp.mastercatalog.application.usecase.ActualizarConsultorioUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.ActualizarEspecialidadUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.ActualizarOdontologoUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.ActualizarPacienteUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.ActualizarServicioOdontologicoUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.ConsultarCatalogoUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.CrearConsultorioUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.CrearEspecialidadUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.CrearOdontologoUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.CrearPacienteUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.CrearServicioOdontologicoUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.DesactivarConsultorioUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.DesactivarEspecialidadUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.DesactivarOdontologoUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.DesactivarPacienteUseCase;
import com.dentalplatform.erp.mastercatalog.application.usecase.DesactivarServicioOdontologicoUseCase;
import com.dentalplatform.erp.mastercatalog.domain.model.Consultorio;
import com.dentalplatform.erp.mastercatalog.domain.model.Especialidad;
import com.dentalplatform.erp.mastercatalog.domain.model.Odontologo;
import com.dentalplatform.erp.mastercatalog.domain.model.Paciente;
import com.dentalplatform.erp.mastercatalog.domain.model.ServicioOdontologico;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Controller
public class MasterCatalogGraphQLController {

    private static final String ANY_ROLE = "hasAnyRole('ADMIN','CAJERO','RECEPCIONISTA','ODONTOLOGO')";
    private static final String ADMIN = "hasRole('ADMIN')";
    private static final String ADMIN_OR_RECEPCIONISTA = "hasAnyRole('ADMIN','RECEPCIONISTA')";

    private final ConsultarCatalogoUseCase consultar;
    private final CrearPacienteUseCase crearPaciente;
    private final CrearEspecialidadUseCase crearEspecialidad;
    private final CrearOdontologoUseCase crearOdontologo;
    private final CrearConsultorioUseCase crearConsultorio;
    private final CrearServicioOdontologicoUseCase crearServicio;
    private final ActualizarPacienteUseCase actualizarPaciente;
    private final ActualizarOdontologoUseCase actualizarOdontologo;
    private final ActualizarEspecialidadUseCase actualizarEspecialidad;
    private final ActualizarConsultorioUseCase actualizarConsultorio;
    private final ActualizarServicioOdontologicoUseCase actualizarServicio;
    private final DesactivarPacienteUseCase desactivarPaciente;
    private final DesactivarOdontologoUseCase desactivarOdontologo;
    private final DesactivarEspecialidadUseCase desactivarEspecialidad;
    private final DesactivarConsultorioUseCase desactivarConsultorio;
    private final DesactivarServicioOdontologicoUseCase desactivarServicio;

    public MasterCatalogGraphQLController(ConsultarCatalogoUseCase consultar, CrearPacienteUseCase crearPaciente,
                                          CrearEspecialidadUseCase crearEspecialidad,
                                          CrearOdontologoUseCase crearOdontologo,
                                          CrearConsultorioUseCase crearConsultorio,
                                          CrearServicioOdontologicoUseCase crearServicio,
                                          ActualizarPacienteUseCase actualizarPaciente,
                                          ActualizarOdontologoUseCase actualizarOdontologo,
                                          ActualizarEspecialidadUseCase actualizarEspecialidad,
                                          ActualizarConsultorioUseCase actualizarConsultorio,
                                          ActualizarServicioOdontologicoUseCase actualizarServicio,
                                          DesactivarPacienteUseCase desactivarPaciente,
                                          DesactivarOdontologoUseCase desactivarOdontologo,
                                          DesactivarEspecialidadUseCase desactivarEspecialidad,
                                          DesactivarConsultorioUseCase desactivarConsultorio,
                                          DesactivarServicioOdontologicoUseCase desactivarServicio) {
        this.consultar = consultar;
        this.crearPaciente = crearPaciente;
        this.crearEspecialidad = crearEspecialidad;
        this.crearOdontologo = crearOdontologo;
        this.crearConsultorio = crearConsultorio;
        this.crearServicio = crearServicio;
        this.actualizarPaciente = actualizarPaciente;
        this.actualizarOdontologo = actualizarOdontologo;
        this.actualizarEspecialidad = actualizarEspecialidad;
        this.actualizarConsultorio = actualizarConsultorio;
        this.actualizarServicio = actualizarServicio;
        this.desactivarPaciente = desactivarPaciente;
        this.desactivarOdontologo = desactivarOdontologo;
        this.desactivarEspecialidad = desactivarEspecialidad;
        this.desactivarConsultorio = desactivarConsultorio;
        this.desactivarServicio = desactivarServicio;
    }

    @QueryMapping
    @PreAuthorize(ANY_ROLE)
    public List<Paciente> listarPacientes(@Argument String busqueda) {
        return consultar.listarPacientes(busqueda);
    }

    @QueryMapping
    @PreAuthorize(ANY_ROLE)
    public Paciente obtenerPacientePorId(@Argument UUID id) {
        return consultar.obtenerPaciente(id).orElse(null);
    }

    @QueryMapping
    @PreAuthorize(ANY_ROLE)
    public List<Odontologo> listarOdontologos(@Argument Boolean soloActivos) {
        return consultar.listarOdontologos(soloActivos == null || soloActivos);
    }

    @QueryMapping
    @PreAuthorize(ANY_ROLE)
    public List<Especialidad> listarEspecialidades() {
        return consultar.listarEspecialidades();
    }

    @QueryMapping
    @PreAuthorize(ANY_ROLE)
    public List<Consultorio> listarConsultorios() {
        return consultar.listarConsultorios();
    }

    @QueryMapping
    @PreAuthorize(ANY_ROLE)
    public List<ServicioOdontologico> listarServiciosOdontologicos(@Argument UUID especialidadId) {
        return consultar.listarServicios(especialidadId);
    }

    @MutationMapping
    @PreAuthorize(ADMIN_OR_RECEPCIONISTA)
    public Paciente crearPaciente(@Argument CrearPacienteInput input) {
        return crearPaciente.ejecutar(input.toCommand());
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public Especialidad crearEspecialidad(@Argument String codigo, @Argument String nombre,
                                          @Argument String descripcion) {
        return crearEspecialidad.ejecutar(new CrearEspecialidadCommand(codigo, nombre, descripcion));
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public Odontologo crearOdontologo(@Argument CrearOdontologoInput input) {
        return crearOdontologo.ejecutar(input.toCommand());
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public Consultorio crearConsultorio(@Argument CrearConsultorioInput input) {
        return crearConsultorio.ejecutar(input.toCommand());
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public ServicioOdontologico crearServicioOdontologico(@Argument CrearServicioInput input) {
        return crearServicio.ejecutar(input.toCommand());
    }

    @MutationMapping
    @PreAuthorize(ADMIN_OR_RECEPCIONISTA)
    public Paciente actualizarPaciente(@Argument UUID id, @Argument ActualizarPacienteInput input) {
        return actualizarPaciente.ejecutar(input.toCommand(id));
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public Odontologo actualizarOdontologo(@Argument UUID id, @Argument ActualizarOdontologoInput input) {
        return actualizarOdontologo.ejecutar(input.toCommand(id));
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public Especialidad actualizarEspecialidad(@Argument UUID id, @Argument ActualizarEspecialidadInput input) {
        return actualizarEspecialidad.ejecutar(input.toCommand(id));
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public Consultorio actualizarConsultorio(@Argument UUID id, @Argument ActualizarConsultorioInput input) {
        return actualizarConsultorio.ejecutar(input.toCommand(id));
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public ServicioOdontologico actualizarServicioOdontologico(@Argument UUID id,
                                                               @Argument ActualizarServicioInput input) {
        return actualizarServicio.ejecutar(input.toCommand(id));
    }

    @MutationMapping
    @PreAuthorize(ADMIN_OR_RECEPCIONISTA)
    public boolean eliminarPaciente(@Argument UUID id) {
        desactivarPaciente.ejecutar(id);
        return true;
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public boolean eliminarOdontologo(@Argument UUID id) {
        desactivarOdontologo.ejecutar(id);
        return true;
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public boolean eliminarEspecialidad(@Argument UUID id) {
        desactivarEspecialidad.ejecutar(id);
        return true;
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public boolean eliminarConsultorio(@Argument UUID id) {
        desactivarConsultorio.ejecutar(id);
        return true;
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public boolean eliminarServicioOdontologico(@Argument UUID id) {
        desactivarServicio.ejecutar(id);
        return true;
    }

    @SchemaMapping(typeName = "ServicioOdontologico", field = "precioBaseBs")
    public BigDecimal precioBaseBs(ServicioOdontologico servicio) {
        return servicio.getPrecioBase().amount();
    }
}
