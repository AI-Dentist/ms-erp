package com.dentalplatform.erp.configsecurity.presentation;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@WithMockUser(username = "admin", roles = "ADMIN")
class ConfigSecurityGraphQLIntegrationTest extends IntegrationTestBase {

    private static final String CREAR_USUARIO = """
            mutation($input: CrearUsuarioInput!) {
              crearUsuario(input: $input) { id username email activo rol { codigo permisos } }
            }""";

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private Map<String, Object> usuarioInput(String suffix, String rol) {
        return Map.of("username", "user-" + suffix, "email", "user-" + suffix + "@clinica.bo",
                "nombreCompleto", "Usuario " + suffix, "password", "Secreta2026!", "rolCodigo", rol);
    }

    private int countUsuarios(String suffix) {
        return jdbc.queryForObject("SELECT count(*) FROM usuarios WHERE username = ?", Integer.class, "user-" + suffix);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    @Test
    void returnsSeededCompanyConfigFromSantaCruz() {
        graphQl.document("{ obtenerConfiguracionEmpresa { nombreComercial nit direccion monedaSimbolo } }")
                .execute()
                .path("obtenerConfiguracionEmpresa.monedaSimbolo").entity(String.class).isEqualTo("Bs")
                .path("obtenerConfiguracionEmpresa.direccion").entity(String.class)
                .satisfies(d -> assertThat(d).contains("Santa Cruz de la Sierra"))
                .path("obtenerConfiguracionEmpresa.nit").entity(String.class)
                .satisfies(n -> assertThat(n).matches("\\d{5,20}"));
    }

    @Test
    void updatingCompanyConfigPersistsAndWritesAuditLog() {
        String marker = "Clinica " + unique();
        graphQl.document("""
                        mutation($input: EmpresaConfigInput!) {
                          actualizarConfiguracionEmpresa(input: $input) { nombreComercial monedaSimbolo }
                        }""")
                .variable("input", Map.of("nombreComercial", marker, "razonSocial", "Sonrisa S.R.L.",
                        "nit", "1023456029", "direccion", "Av. San Martin 155, Santa Cruz de la Sierra",
                        "telefonoContacto", "+591 3 3412233", "emailContacto", "info@sonrisa.bo"))
                .execute()
                .path("actualizarConfiguracionEmpresa.nombreComercial").entity(String.class).isEqualTo(marker);

        graphQl.document("{ obtenerConfiguracionEmpresa { nombreComercial } }").execute()
                .path("obtenerConfiguracionEmpresa.nombreComercial").entity(String.class).isEqualTo(marker);
        assertThat(count("empresa_config")).isEqualTo(1);

        graphQl.document("{ listarAuditLogs(limite: 50) { accion entidadAfectada detalleJson createdAt } }").execute()
                .path("listarAuditLogs[?(@.accion=='EMPRESA_CONFIG_ACTUALIZADA')].detalleJson")
                .entityList(String.class)
                .satisfies(l -> assertThat(l).anyMatch(json -> json != null && json.contains(marker)));
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit_logs WHERE accion = 'EMPRESA_CONFIG_ACTUALIZADA' AND detalle_json->>'nombreComercial' = ?",
                Integer.class, marker)).isEqualTo(1);
    }

    @Test
    void createsUserReturningRolePermissionsAndNeverExposingPassword() {
        String suffix = unique();
        graphQl.document(CREAR_USUARIO).variable("input", usuarioInput(suffix, "CAJERO")).execute()
                .path("crearUsuario.username").entity(String.class).isEqualTo("user-" + suffix)
                .path("crearUsuario.rol.codigo").entity(String.class).isEqualTo("CAJERO")
                .path("crearUsuario.rol.permisos").entityList(String.class)
                .satisfies(p -> assertThat(p).isNotEmpty().allMatch(c -> c.equals(c.toUpperCase())))
                .path("crearUsuario.activo").entity(Boolean.class).isEqualTo(true);

        String hash = jdbc.queryForObject("SELECT password_hash FROM usuarios WHERE username = ?", String.class,
                "user-" + suffix);
        assertThat(hash).startsWith("$2").isNotEqualTo("Secreta2026!");

        // the schema has no password field: asking for it is a validation error and creates nothing
        String other = unique();
        graphQl.document("mutation($input: CrearUsuarioInput!) { crearUsuario(input: $input) { passwordHash } }")
                .variable("input", usuarioInput(other, "CAJERO")).execute()
                .errors().satisfy(errors -> assertThat(errors).isNotEmpty());
        assertThat(countUsuarios(other)).isZero();
    }

    @Test
    void duplicateUsernameReturnsBadRequestAndCreatesNoRow() {
        String suffix = unique();
        graphQl.document(CREAR_USUARIO).variable("input", usuarioInput(suffix, "CAJERO")).execute()
                .path("crearUsuario.id").hasValue();
        assertThat(countUsuarios(suffix)).isEqualTo(1);
        int usersBefore = count("usuarios");
        int auditBefore = count("audit_logs");

        var input = new HashMap<>(usuarioInput(suffix, "RECEPCIONISTA"));
        input.put("email", "otro-" + suffix + "@clinica.bo");
        graphQl.document(CREAR_USUARIO).variable("input", input).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST
                        && e.getMessage() != null && e.getMessage().contains("user-" + suffix))
                .verify()
                .path("crearUsuario").pathDoesNotExist();

        assertThat(countUsuarios(suffix)).isEqualTo(1);
        assertThat(count("usuarios")).isEqualTo(usersBefore);
        assertThat(count("audit_logs")).isEqualTo(auditBefore);
    }

    @Test
    void listsAdminRoleWithAllNinePermissions() {
        graphQl.document("{ listarRoles { codigo nombre permisos } }").execute()
                .path("listarRoles").entityList(Map.class)
                .satisfies(roles -> assertThat(roles)
                        .filteredOn(r -> "ADMIN".equals(r.get("codigo")))
                        .singleElement()
                        .satisfies(r -> assertThat((List<?>) r.get("permisos")).hasSize(9).doesNotHaveDuplicates()));
    }

    @Test
    @WithMockUser(username = "cajero", roles = "CAJERO")
    void nonAdminCannotCreateUsersAndNoRowIsCreated() {
        String suffix = unique();
        int usersBefore = count("usuarios");
        graphQl.document(CREAR_USUARIO).variable("input", usuarioInput(suffix, "CAJERO")).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify()
                .path("crearUsuario").pathDoesNotExist();
        assertThat(countUsuarios(suffix)).isZero();
        assertThat(count("usuarios")).isEqualTo(usersBefore);
    }

    @Test
    @WithMockUser(username = "cajero", roles = "CAJERO")
    void cashierCanReadRolesAndCompanyConfigButNotUsers() {
        graphQl.document("{ obtenerConfiguracionEmpresa { monedaSimbolo } }").execute()
                .path("obtenerConfiguracionEmpresa.monedaSimbolo").entity(String.class).isEqualTo("Bs");
        graphQl.document("{ listarRoles { codigo } }").execute()
                .path("listarRoles[*].codigo").entityList(String.class)
                .satisfies(c -> assertThat((List<String>) c).contains("ADMIN", "CAJERO"));
        graphQl.document("{ listarUsuarios { username } }").execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
    }
}
