package com.dentalplatform.erp.configsecurity.presentation.graphql;

import com.dentalplatform.erp.configsecurity.application.usecase.CrearUsuarioConIdentidadUseCase;
import com.dentalplatform.erp.configsecurity.domain.model.Usuario;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;

@Controller
public class UsuarioProvisioningGraphQLController {
    private final CrearUsuarioConIdentidadUseCase crear;

    public UsuarioProvisioningGraphQLController(CrearUsuarioConIdentidadUseCase crear) { this.crear = crear; }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public Usuario crearUsuarioConIdentidad(@Argument CrearUsuarioConIdentidadInput input) {
        return crear.ejecutar(input.usuario().toCommand(), input.keycloakSubject(), input.odontologoId());
    }
}
