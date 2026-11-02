package com.dentalplatform.erp.configsecurity.presentation;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Complementary public GraphQL checks; signed-token HTTP rules live in UsuarioIdentitySecurityTest. */
@WithMockUser(username = "admin", roles = "ADMIN")
class UsuarioIdentityGraphQLIntegrationTest extends IntegrationTestBase {
    private String user() {
        String name = "user-gql-" + UUID.randomUUID().toString().substring(0, 18);
        return graphQl.document("""
                mutation($i: CrearUsuarioInput!) { crearUsuario(input: $i) { id } }
                """).variable("i", Map.of("username", name, "email", name + "@example.test",
                        "nombreCompleto", "Synthetic User", "password", "Synthetic2026!", "rolCodigo", "CAJERO"))
                .execute().path("crearUsuario.id").entity(String.class).get();
    }

    private String doctor() {
        String suffix = UUID.randomUUID().toString().substring(0, 18);
        return graphQl.document("""
                mutation($i: CrearOdontologoInput!) { crearOdontologo(input: $i) { id } }
                """).variable("i", Map.of("ci", "CI-" + suffix, "matriculaProfesional", "MAT-" + suffix,
                        "nombres", "Synthetic", "apellidos", "Doctor", "telefono", "+59170000000",
                        "porcentajeComision", 30, "especialidadesIds", List.of()))
                .execute().path("crearOdontologo.id").entity(String.class).get();
    }

    private Object state() {
        var response = graphQl.document("""
                { listarUsuarios { id username email nombreCompleto activo rol { codigo } }
                  listarOdontologos(soloActivos: false) { id ci activo }
                  listarAuditLogs(limite: 500) { id accion entidadAfectada entidadId detalleJson createdAt } }
                """).execute();
        return Map.of("users", response.path("listarUsuarios").entityList(Object.class).get(),
                "doctors", response.path("listarOdontologos").entityList(Object.class).get(),
                "audits", response.path("listarAuditLogs").entityList(Object.class).get());
    }

    private void bind(String id, String subject) {
        graphQl.document("""
                mutation($i: VincularIdentidadUsuarioInput!) { vincularIdentidadUsuario(input: $i) }
                """).variable("i", Map.of("usuarioId", id, "keycloakSubject", subject)).execute()
                .path("vincularIdentidadUsuario").entity(String.class).isEqualTo(id);
    }

    private void self(String subject, String field, String expected) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        var jwt = Jwt.withTokenValue("synthetic-service-test").header("alg", "RS256")
                .subject(subject).claim("preferred_username", "unrelated").build();
        context.setAuthentication(new JwtAuthenticationToken(jwt,
                AuthorityUtils.createAuthorityList("ROLE_ADMIN")));
        SecurityContextHolder.setContext(context);
        try {
            graphQl.document("{ " + field + " }").execute().path(field).entity(String.class).isEqualTo(expected);
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }

    @Test
    @WithMockUser(username = "user-identity-nonjwt", roles = "ADMIN")
    void nonJwtAdminCannotUseUsernameAsSubjectOrWriteOnRead() {
        String id = user();
        bind(id, "user-identity-nonjwt");
        var before = state();
        graphQl.document("{ obtenerMiUsuarioId }").execute().errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getErrorType().toString()).isEqualTo("FORBIDDEN");
            assertThat(errors.getFirst().getMessage()).isEqualTo("Se requiere un JWT con subject");
        }).path("obtenerMiUsuarioId").pathDoesNotExist();
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void existingPairedDoctorProvisionerStillResolvesBothIdentitiesAndExactUserRepeatIsReadOnly() {
        String id = user();
        String doctor = doctor();
        String subject = "paired:" + UUID.randomUUID();
        graphQl.document("""
                mutation($i: VincularIdentidadOdontologoInput!) { vincularIdentidadOdontologo(input: $i) }
                """).variable("i", Map.of("usuarioId", id, "odontologoId", doctor, "keycloakSubject", subject))
                .execute().path("vincularIdentidadOdontologo").entity(String.class).isEqualTo(doctor);
        var before = state();
        bind(id, subject);
        self(subject, "obtenerMiUsuarioId", id);
        self(subject, "obtenerMiOdontologoId", doctor);
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void userOnlyBindingDoesNotRelaxExistingPairedProvisionersPartialLinkRejection() {
        String id = user();
        String doctor = doctor();
        String subject = "user-only:" + UUID.randomUUID();
        bind(id, subject);
        var before = state();
        graphQl.document("""
                mutation($i: VincularIdentidadOdontologoInput!) { vincularIdentidadOdontologo(input: $i) }
                """).variable("i", Map.of("usuarioId", id, "odontologoId", doctor, "keycloakSubject", subject))
                .execute().errors().satisfy(errors -> {
                    assertThat(errors).hasSize(1);
                    assertThat(errors.getFirst().getErrorType().toString()).isEqualTo("BAD_REQUEST");
                    assertThat(errors.getFirst().getMessage()).isEqualTo("La identidad odontológica ya está vinculada");
                }).path("vincularIdentidadOdontologo").pathDoesNotExist();
        self(subject, "obtenerMiUsuarioId", id);
        assertThat(state()).isEqualTo(before);
    }

    @Test
    void ordinaryUserUpdatePreservesCanonicalIdentityAndOneBindingAudit() {
        String id = user();
        String subject = "updated:" + UUID.randomUUID();
        bind(id, subject);
        graphQl.document("""
                mutation($id: UUID!) { actualizarUsuario(id: $id,
                  input: {rolCodigo: "RECEPCIONISTA", activo: true, nombreCompleto: "Edited Synthetic"}) {
                  id nombreCompleto rol { codigo } } }
                """).variable("id", id).execute()
                .path("actualizarUsuario.nombreCompleto").entity(String.class).isEqualTo("Edited Synthetic")
                .path("actualizarUsuario.rol.codigo").entity(String.class).isEqualTo("RECEPCIONISTA");
        var before = state();
        self(subject, "obtenerMiUsuarioId", id);
        bind(id, subject);
        assertThat(state()).isEqualTo(before);
        var audits = graphQl.document("{ listarAuditLogs(limite: 500) { accion entidadAfectada entidadId detalleJson } }")
                .execute().path("listarAuditLogs").entityList(Map.class).get();
        assertThat(audits.stream().filter(a -> "IDENTIDAD_USUARIO_VINCULADA".equals(a.get("accion"))
                && id.equals(a.get("entidadId"))).toList()).singleElement().satisfies(a -> {
                    assertThat(a.get("entidadAfectada")).isEqualTo("usuarios");
                    assertThat(a.get("detalleJson").toString()).contains(subject);
                });
    }
}
