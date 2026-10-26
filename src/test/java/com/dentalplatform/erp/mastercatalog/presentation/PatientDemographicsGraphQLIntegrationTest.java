package com.dentalplatform.erp.mastercatalog.presentation;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses only newly created synthetic patients in the per-JVM embedded PostgreSQL database. */
@WithMockUser(username = "synthetic-admin", roles = "ADMIN")
class PatientDemographicsGraphQLIntegrationTest extends IntegrationTestBase {

    private static final String FIELDS = "id ciNit nombres apellidos telefonoWhatsapp fechaNacimiento genero";
    private static final String LEGACY_FIELDS = "id ciNit nombres apellidos telefonoWhatsapp";
    private static final String CREATE = "mutation($input: CrearPacienteInput!) { crearPaciente(input: $input) { %s } }";
    private static final String UPDATE = """
            mutation($id: UUID!, $input: ActualizarPacienteInput!) {
              actualizarPaciente(id: $id, input: $input) { %s }
            }""";
    private static final String DETAIL = "query($id: UUID!) { obtenerPacientePorId(id: $id) { %s } }";
    private static final String LIST = "query($b: String) { listarPacientes(busqueda: $b) { %s } }";
    private static final String DATE = "1992-02-29";
    private static final String GENDER = "Autodescrito";

    private Map<String, Object> input(boolean demographics) {
        Map<String, Object> input = new HashMap<>(Map.of(
                "ciNit", "SYN-" + UUID.randomUUID().toString().substring(0, 20),
                "nombres", "Synthetic", "apellidos", "Fixture", "telefonoWhatsapp", "+59170000000"));
        if (demographics) {
            input.put("fechaNacimiento", DATE);
            input.put("genero", GENDER);
        }
        return input;
    }

    private String create(Map<String, Object> input) {
        return graphQl.document(CREATE.formatted(LEGACY_FIELDS)).variable("input", input).execute()
                .errors().verify().path("crearPaciente.id").entity(String.class).get();
    }

    private GraphQlTester.Traversable detail(String id) {
        return graphQl.document(DETAIL.formatted(FIELDS)).variable("id", id).execute().errors().verify();
    }

    private GraphQlTester.Traversable listing(Map<String, Object> input) {
        return graphQl.document(LIST.formatted(FIELDS)).variable("b", input.get("ciNit"))
                .execute().errors().verify();
    }

    private void assertDemographics(GraphQlTester.Traversable response, String path, String date, String gender) {
        if (date == null) {
            response.path(path + ".fechaNacimiento").valueIsNull();
        } else {
            response.path(path + ".fechaNacimiento").entity(String.class).isEqualTo(date);
        }
        if (gender == null) {
            response.path(path + ".genero").valueIsNull();
        } else {
            response.path(path + ".genero").entity(String.class).isEqualTo(gender);
        }
    }

    private void assertReadback(String id, Map<String, Object> input, String date, String gender) {
        assertDemographics(detail(id), "obtenerPacientePorId", date, gender);
        GraphQlTester.Traversable list = listing(input);
        list.path("listarPacientes").entityList(Object.class).hasSize(1);
        list.path("listarPacientes[0].id").entity(String.class).isEqualTo(id);
        assertDemographics(list, "listarPacientes[0]", date, gender);
    }

    @Test
    void createDetailAndListReturnExactPersistedDemographics() {
        Map<String, Object> input = input(true);
        GraphQlTester.Traversable created = graphQl.document(CREATE.formatted(FIELDS)).variable("input", input)
                .execute().errors().verify();
        assertDemographics(created, "crearPaciente", DATE, GENDER);
        String id = created.path("crearPaciente.id").entity(String.class).get();
        assertReadback(id, input, DATE, GENDER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "RECEPCIONISTA"})
    void allowedWritersCanResendReadValuesWhileChangingAnotherField(String role) {
        asRole(role, () -> {
            Map<String, Object> input = input(true);
            String id = create(input);
            GraphQlTester.Traversable read = detail(id);
            input.put("fechaNacimiento", read.path("obtenerPacientePorId.fechaNacimiento").entity(String.class).get());
            input.put("genero", read.path("obtenerPacientePorId.genero").entity(String.class).get());
            input.put("nombres", "Synthetic edited");
            GraphQlTester.Traversable updated = graphQl.document(UPDATE.formatted(FIELDS)).variable("id", id)
                    .variable("input", input).execute().errors().verify();
            updated.path("actualizarPaciente.nombres").entity(String.class).isEqualTo("Synthetic edited");
            assertDemographics(updated, "actualizarPaciente", DATE, GENDER);
            assertReadback(id, input, DATE, GENDER);
            detail(id).path("obtenerPacientePorId.nombres").entity(String.class).isEqualTo("Synthetic edited");
        });
    }

    @Test
    void explicitNullsRemainNullInCreateUpdateDetailAndList() {
        Map<String, Object> input = input(false);
        input.put("fechaNacimiento", null);
        input.put("genero", null);
        GraphQlTester.Traversable created = graphQl.document(CREATE.formatted(FIELDS)).variable("input", input)
                .execute().errors().verify();
        assertDemographics(created, "crearPaciente", null, null);
        String id = created.path("crearPaciente.id").entity(String.class).get();
        assertReadback(id, input, null, null);
        input.put("nombres", "Synthetic null edited");
        assertDemographics(graphQl.document(UPDATE.formatted(FIELDS)).variable("id", id).variable("input", input)
                .execute().errors().verify(), "actualizarPaciente", null, null);
        assertReadback(id, input, null, null);
        detail(id).path("obtenerPacientePorId.nombres").entity(String.class).isEqualTo("Synthetic null edited");
    }

    @Test
    void legacySelectionsStillWorkAndOmissionStillReplacesDemographicsWithNull() {
        Map<String, Object> input = input(true);
        String id = create(input);
        graphQl.document(DETAIL.formatted(LEGACY_FIELDS)).variable("id", id).execute().errors().verify()
                .path("obtenerPacientePorId.ciNit").entity(String.class).isEqualTo(input.get("ciNit"));
        graphQl.document(LIST.formatted(LEGACY_FIELDS)).variable("b", input.get("ciNit"))
                .execute().errors().verify().path("listarPacientes[0].id").entity(String.class).isEqualTo(id);
        input.remove("fechaNacimiento");
        input.remove("genero");
        graphQl.document(UPDATE.formatted(LEGACY_FIELDS)).variable("id", id).variable("input", input)
                .execute().errors().verify().path("actualizarPaciente.id").entity(String.class).isEqualTo(id);
        assertReadback(id, input, null, null);
    }

    @Test
    void introspectionExposesNullableDateAndStringScalars() {
        GraphQlTester.Traversable response = graphQl.document("""
                { __type(name: "Paciente") { fields { name type { kind name ofType { kind name } } } } }
                """).execute().errors().verify();
        for (Map.Entry<String, String> field : Map.of("fechaNacimiento", "Date", "genero", "String").entrySet()) {
            String path = "__type.fields[?(@.name=='" + field.getKey() + "')].type";
            response.path(path + ".kind").entityList(String.class).containsExactly("SCALAR");
            response.path(path + ".name").entityList(String.class).containsExactly(field.getValue());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ADMIN", "RECEPCIONISTA", "CAJERO", "ODONTOLOGO"})
    void existingReadRolesCanReadBothDemographics(String role) {
        Map<String, Object> input = input(true);
        String id = create(input);
        asRole(role, () -> assertReadback(id, input, DATE, GENDER));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CAJERO", "ODONTOLOGO", "UNRELATED"})
    void deniedWritersCannotCreateOrChangeStoredPatient(String role) {
        Map<String, Object> input = input(true);
        String id = create(input);
        Map<?, ?> before = detail(id).path("obtenerPacientePorId").entity(Map.class).get();
        Map<String, Object> changed = new HashMap<>(input);
        changed.put("nombres", "Must not persist");
        changed.put("fechaNacimiento", "1980-01-01");
        changed.put("genero", "Must not persist");
        Map<String, Object> rejectedCreate = input(true);
        asRole(role, () -> {
            forbidden(graphQl.document(UPDATE.formatted(FIELDS)).variable("id", id).variable("input", changed).execute());
            forbidden(graphQl.document(CREATE.formatted(FIELDS)).variable("input", rejectedCreate).execute());
        });
        assertThat(detail(id).path("obtenerPacientePorId").entity(Map.class).get()).isEqualTo(before);
        listing(rejectedCreate).path("listarPacientes").entityList(Object.class).hasSize(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"UNRELATED"})
    void callersWithoutAnExistingReadRoleCannotReadDemographics(String role) {
        Map<String, Object> input = input(true);
        String id = create(input);
        asRole(role, () -> {
            forbidden(graphQl.document(DETAIL.formatted(FIELDS)).variable("id", id).execute());
            forbidden(graphQl.document(LIST.formatted(FIELDS)).variable("b", input.get("ciNit")).execute());
        });
        assertReadback(id, input, DATE, GENDER);
    }

    private void forbidden(GraphQlTester.Response response) {
        response.errors().satisfy(errors -> {
            assertThat(errors).hasSize(1);
            assertThat(errors.getFirst().getErrorType()).isEqualTo(ErrorType.FORBIDDEN);
        });
    }

    private void asRole(String role, Runnable action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken("synthetic-" + role, "unused",
                List.of(new SimpleGrantedAuthority("ROLE_" + role))));
        SecurityContextHolder.setContext(context);
        try {
            action.run();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
