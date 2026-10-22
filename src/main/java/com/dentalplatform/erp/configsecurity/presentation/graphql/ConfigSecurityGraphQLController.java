package com.dentalplatform.erp.configsecurity.presentation.graphql;

import com.dentalplatform.erp.configsecurity.application.usecase.ActualizarConfiguracionEmpresaUseCase;
import com.dentalplatform.erp.configsecurity.application.usecase.ConsultarSeguridadUseCase;
import com.dentalplatform.erp.configsecurity.application.usecase.CrearUsuarioUseCase;
import com.dentalplatform.erp.configsecurity.application.usecase.ObtenerConfiguracionEmpresaUseCase;
import com.dentalplatform.erp.configsecurity.domain.model.AuditLog;
import com.dentalplatform.erp.configsecurity.domain.model.EmpresaConfig;
import com.dentalplatform.erp.configsecurity.domain.model.Rol;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
public class ConfigSecurityGraphQLController {

    private static final String ANY_ROLE = "hasAnyRole('ADMIN','CAJERO','RECEPCIONISTA','ODONTOLOGO')";
    private static final String ADMIN = "hasRole('ADMIN')";

    private final ObtenerConfiguracionEmpresaUseCase obtenerConfiguracion;
    private final ActualizarConfiguracionEmpresaUseCase actualizarConfiguracion;
    private final CrearUsuarioUseCase crearUsuario;
    private final ConsultarSeguridadUseCase consultarSeguridad;

    public ConfigSecurityGraphQLController(ObtenerConfiguracionEmpresaUseCase obtenerConfiguracion,
                                           ActualizarConfiguracionEmpresaUseCase actualizarConfiguracion,
                                           CrearUsuarioUseCase crearUsuario, ConsultarSeguridadUseCase consultarSeguridad) {
        this.obtenerConfiguracion = obtenerConfiguracion;
        this.actualizarConfiguracion = actualizarConfiguracion;
        this.crearUsuario = crearUsuario;
        this.consultarSeguridad = consultarSeguridad;
    }

    @QueryMapping
    @PreAuthorize(ANY_ROLE)
    public EmpresaConfig obtenerConfiguracionEmpresa() {
        return obtenerConfiguracion.ejecutar();
    }

    @QueryMapping
    @PreAuthorize(ADMIN)
    public List<Usuario> listarUsuarios() {
        return consultarSeguridad.listarUsuarios();
    }

    @QueryMapping
    @PreAuthorize(ANY_ROLE)
    public List<Rol> listarRoles() {
        return consultarSeguridad.listarRoles();
    }

    @QueryMapping
    @PreAuthorize(ADMIN)
    public List<AuditLog> listarAuditLogs(@Argument Integer limite) {
        return consultarSeguridad.listarAuditLogs(limite == null ? 50 : limite);
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public EmpresaConfig actualizarConfiguracionEmpresa(@Argument EmpresaConfigInput input) {
        return actualizarConfiguracion.ejecutar(input.toCommand());
    }

    @MutationMapping
    @PreAuthorize(ADMIN)
    public Usuario crearUsuario(@Argument CrearUsuarioInput input) {
        return crearUsuario.ejecutar(input.toCommand());
    }

    @SchemaMapping(typeName = "Rol", field = "permisos")
    public List<String> permisos(Rol rol) {
        return rol.codigosPermisos();
    }

    @SchemaMapping(typeName = "Usuario", field = "rol")
    public Rol rol(Usuario usuario) {
        return usuario.getRol();
    }
}
