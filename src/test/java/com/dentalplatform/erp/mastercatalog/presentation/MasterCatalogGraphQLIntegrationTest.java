package com.dentalplatform.erp.mastercatalog.presentation;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.security.test.context.support.WithMockUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

@WithMockUser(username = "admin", roles = "ADMIN")
class MasterCatalogGraphQLIntegrationTest extends IntegrationTestBase {

    private static final String EXCHANGE = "dental.topic.exchange";
    private static final String ORTODONCIA_ID = "40000000-0000-0000-0000-000000000002";
    private static final String ESTETICA_ID = "40000000-0000-0000-0000-000000000004";

    private static final String CREAR_PACIENTE = """
            mutation($input: CrearPacienteInput!) {
              crearPaciente(input: $input) { id ciNit nombres apellidos telefonoWhatsapp email }
            }""";
    private static final String CREAR_ODONTOLOGO = """
            mutation($input: CrearOdontologoInput!) {
              crearOdontologo(input: $input) {
                id matriculaProfesional porcentajeComision especialidades { nombre }
              }
            }""";

    @Autowired
    private JsonMapper jsonMapper;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private int count(String table, String column, Object value) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table + " WHERE " + column + " = ?", Integer.class, value);
    }

    private int auditCount(String accion, Object entidadId) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = ? AND entidad_id = ?",
                Integer.class, accion, entidadId);
    }

    private JsonNode sentPayload(String routingKey) {
        verifyNoInteractions(rabbitTemplate);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT event_id, exchange_name, payload::text, attempts, sent_at
                FROM catalog_event_outbox WHERE routing_key = ?
                ORDER BY created_at DESC LIMIT 1""", routingKey);
        JsonNode payload = jsonMapper.readTree(row.get("payload").toString());
        assertThat(payload.get("eventId").asString()).isEqualTo(row.get("event_id").toString());
        assertThat(row.get("exchange_name")).isEqualTo(EXCHANGE);
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("sent_at")).isNull();
        return payload;
    }

    private static Set<String> fieldNames(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    private Map<String, Object> pacienteInput(String ciNit) {
        return Map.of("ciNit", ciNit, "nombres", "Carlos Andrés", "apellidos", "Justiniano " + ciNit,
                "fechaNacimiento", "1995-04-12", "telefonoWhatsapp", "+59170012345", "email", "c@gmail.com");
    }

    private Map<String, Object> odontologoInput(String suffix, List<String> especialidades) {
        return Map.of("ci", "CI-" + suffix, "matriculaProfesional", "mat-" + suffix, "nombres", "Valeria",
                "apellidos", "Roca " + suffix, "telefono", "+59170811223", "porcentajeComision", 35,
                "especialidadesIds", especialidades);
    }

    @Test
    void createPatientPersistsAuditsAndPublishesExactlyOneSpecEvent() {
        String ciNit = "PAC-" + unique();
        String id = graphQl.document(CREAR_PACIENTE).variable("input", pacienteInput(ciNit)).execute()
                .path("crearPaciente.ciNit").entity(String.class).isEqualTo(ciNit)
                .path("crearPaciente.id").entity(String.class).get();

        assertThat(count("pacientes", "ci_nit", ciNit)).isEqualTo(1);
        assertThat(auditCount("PACIENTE_CREADO", UUID.fromString(id))).isEqualTo(1);
        JsonNode event = sentPayload("catalogo.paciente.creado");
        assertThat(fieldNames(event)).containsExactlyInAnyOrder("eventId", "pacienteId", "ciNit", "nombreCompleto",
                "telefonoWhatsapp", "fechaNacimiento");
        assertThat(UUID.fromString(event.get("eventId").asString())).isNotNull();
        assertThat(event.get("pacienteId").asString()).isEqualTo(id);
        assertThat(event.get("ciNit").asString()).isEqualTo(ciNit);
        assertThat(event.get("nombreCompleto").asString()).isEqualTo("Carlos Andrés Justiniano " + ciNit);
        assertThat(event.get("telefonoWhatsapp").asString()).isEqualTo("+59170012345");
        assertThat(event.get("fechaNacimiento").asString()).isEqualTo("1995-04-12");
    }

    @Test
    void duplicateCiNitIsBadRequestWithoutNewRowAuditOrMessage() {
        String ciNit = "PAC-" + unique();
        graphQl.document(CREAR_PACIENTE).variable("input", pacienteInput(ciNit)).execute()
                .path("crearPaciente.id").hasValue();
        int rowsBefore = jdbc.queryForObject("SELECT count(*) FROM pacientes", Integer.class);
        int auditBefore = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        List<Map<String, Object>> outboxBefore = jdbc.queryForList("SELECT * FROM catalog_event_outbox ORDER BY event_id");
        reset(rabbitTemplate);

        graphQl.document(CREAR_PACIENTE).variable("input", pacienteInput(" " + ciNit + " ")).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST
                        && e.getMessage() != null && e.getMessage().contains(ciNit))
                .verify()
                .path("crearPaciente").pathDoesNotExist();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pacientes", Integer.class)).isEqualTo(rowsBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(auditBefore);
        assertThat(jdbc.queryForList("SELECT * FROM catalog_event_outbox ORDER BY event_id")).isEqualTo(outboxBefore);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void createDentistWithSeededSpecialtiesReturnsThemAndEventCarriesTheirNames() {
        String suffix = unique();
        graphQl.document(CREAR_ODONTOLOGO)
                .variable("input", odontologoInput(suffix, List.of(ORTODONCIA_ID, ESTETICA_ID))).execute()
                .path("crearOdontologo.matriculaProfesional").entity(String.class).isEqualTo("MAT-" + suffix)
                .path("crearOdontologo.especialidades[*].nombre").entityList(String.class)
                .satisfies(n -> assertThat(n).containsExactlyInAnyOrder("Ortodoncia", "Estética Dental"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM odontologo_especialidades oe JOIN odontologos o ON o.id = oe.odontologo_id
                WHERE o.matricula_profesional = ?""", Integer.class, "MAT-" + suffix)).isEqualTo(2);
        JsonNode event = sentPayload("catalogo.odontologo.creado");
        assertThat(fieldNames(event)).containsExactlyInAnyOrder("eventId", "odontologoId", "nombreCompleto",
                "matriculaProfesional", "especialidades");
        assertThat(event.get("nombreCompleto").asString()).isEqualTo("Valeria Roca " + suffix);
        assertThat(event.get("matriculaProfesional").asString()).isEqualTo("MAT-" + suffix);
        assertThat(event.get("especialidades").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("Ortodoncia", "Estética Dental");
    }

    @Test
    void createDentistWithUnknownSpecialtyIsBadRequestAndNothingIsPersistedOrSent() {
        List<Map<String, Object>> outboxBefore = jdbc.queryForList("SELECT * FROM catalog_event_outbox ORDER BY event_id");
        int auditsBefore = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        String suffix = unique();
        graphQl.document(CREAR_ODONTOLOGO)
                .variable("input", odontologoInput(suffix, List.of(ORTODONCIA_ID, UUID.randomUUID().toString())))
                .execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify()
                .path("crearOdontologo").pathDoesNotExist();

        assertThat(count("odontologos", "matricula_profesional", "MAT-" + suffix)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(auditsBefore);
        assertThat(jdbc.queryForList("SELECT * FROM catalog_event_outbox ORDER BY event_id")).isEqualTo(outboxBefore);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void createOfficeIsAvailableAndPublishesEvent() {
        String codigo = "CONS-" + unique();
        graphQl.document("""
                        mutation($input: CrearConsultorioInput!) {
                          crearConsultorio(input: $input) { id codigo nombre ubicacion estado }
                        }""")
                .variable("input", Map.of("codigo", codigo.toLowerCase(), "nombre", "Consultorio nuevo"))
                .execute()
                .path("crearConsultorio.codigo").entity(String.class).isEqualTo(codigo)
                .path("crearConsultorio.estado").entity(String.class).isEqualTo("DISPONIBLE");

        JsonNode event = sentPayload("catalogo.consultorio.creado");
        assertThat(fieldNames(event)).containsExactlyInAnyOrder("eventId", "consultorioId", "codigo", "nombre",
                "estado");
        assertThat(event.get("codigo").asString()).isEqualTo(codigo);
        assertThat(event.get("estado").asString()).isEqualTo("DISPONIBLE");
    }

    @Test
    void createServiceReturnsPriceAsNumberAndPublishesNothing() {
        String codigo = "SRV-" + unique();
        graphQl.document("""
                        mutation($input: CrearServicioInput!) {
                          crearServicioOdontologico(input: $input) {
                            codigo precioBaseBs especialidad { nombre }
                          }
                        }""")
                .variable("input", Map.of("especialidadId", ORTODONCIA_ID, "codigo", codigo, "nombre", "Retenedor",
                        "precioBaseBs", 350.5))
                .execute()
                .path("crearServicioOdontologico.precioBaseBs").entity(Double.class).isEqualTo(350.5)
                .path("crearServicioOdontologico.especialidad.nombre").entity(String.class).isEqualTo("Ortodoncia");

        assertThat(count("servicios_odontologicos", "codigo", codigo)).isEqualTo(1);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void createSpecialtyAuditsDetectsDuplicatesAndPublishesNothing() {
        String codigo = "E" + unique();
        String query = "mutation($c: String!) { crearEspecialidad(codigo: $c, nombre: \"Nueva\") { id codigo } }";
        String id = graphQl.document(query).variable("c", codigo.toLowerCase()).execute()
                .path("crearEspecialidad.codigo").entity(String.class).isEqualTo(codigo)
                .path("crearEspecialidad.id").entity(String.class).get();
        assertThat(auditCount("ESPECIALIDAD_CREADA", UUID.fromString(id))).isEqualTo(1);

        graphQl.document(query).variable("c", codigo).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.BAD_REQUEST).verify();
        assertThat(count("especialidades", "codigo", codigo)).isEqualTo(1);
        verifyNoInteractions(rabbitTemplate);
    }

    @Test
    void searchFindsSeededPatientIgnoringCase() {
        graphQl.document("{ listarPacientes(busqueda: \"justiniano\") { ciNit apellidos } }").execute()
                .path("listarPacientes[*].ciNit").entityList(String.class)
                .satisfies(c -> assertThat(c).contains("8945123 SC"))
                .path("listarPacientes[*].apellidos").entityList(String.class)
                .satisfies(a -> assertThat(a).allMatch(s -> s.toLowerCase().contains("justiniano")));
        graphQl.document("{ listarPacientes(busqueda: \"9312874\") { nombres } }").execute()
                .path("listarPacientes[*].nombres").entityList(String.class)
                .satisfies(n -> assertThat(n).contains("María Fernanda"));
        graphQl.document("{ listarPacientes { ciNit } }").execute()
                .path("listarPacientes").entityList(Object.class)
                .satisfies(l -> assertThat(l).hasSizeGreaterThanOrEqualTo(4));
    }

    @Test
    void getPatientByIdReturnsSeededPatientOrNull() {
        graphQl.document("{ obtenerPacientePorId(id: \"60000000-0000-0000-0000-000000000001\") { ciNit } }")
                .execute().path("obtenerPacientePorId.ciNit").entity(String.class).isEqualTo("8945123 SC");
        graphQl.document("{ obtenerPacientePorId(id: \"" + UUID.randomUUID() + "\") { ciNit } }").execute()
                .path("obtenerPacientePorId").valueIsNull();
    }

    @Test
    void listsSeededCatalogs() {
        graphQl.document("{ listarEspecialidades { codigo } }").execute()
                .path("listarEspecialidades[*].codigo").entityList(String.class)
                .satisfies(c -> assertThat(c).contains("ODG", "ORT", "END", "EST", "CIR"));
        graphQl.document("{ listarConsultorios { codigo estado } }").execute()
                .path("listarConsultorios[*].codigo").entityList(String.class)
                .satisfies(c -> assertThat(c).contains("CONS-01", "RX-01"));
        graphQl.document("{ listarOdontologos { matriculaProfesional especialidades { codigo } } }").execute()
                .path("listarOdontologos[?(@.matriculaProfesional=='COL-OD-4512')].especialidades[*].codigo")
                .entityList(String.class).satisfies(c -> assertThat(c).containsExactlyInAnyOrder("ORT", "EST"));
        graphQl.document("""
                        { listarServiciosOdontologicos(especialidadId: "40000000-0000-0000-0000-000000000003") {
                            codigo precioBaseBs } }""").execute()
                .path("listarServiciosOdontologicos[*].codigo").entityList(String.class)
                .satisfies(c -> assertThat(c).contains("END-UNI", "END-MOL").doesNotContain("ODG-CONS"));
    }

    @Test
    @WithMockUser(username = "cajero", roles = "CAJERO")
    void cashierCannotCreatePatientsButCanReadCatalogs() {
        String ciNit = "PAC-" + unique();
        graphQl.document(CREAR_PACIENTE).variable("input", pacienteInput(ciNit)).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify()
                .path("crearPaciente").pathDoesNotExist();

        assertThat(count("pacientes", "ci_nit", ciNit)).isZero();
        verifyNoInteractions(rabbitTemplate);
        graphQl.document("{ listarEspecialidades { codigo } }").execute()
                .path("listarEspecialidades[*].codigo").entityList(String.class)
                .satisfies(c -> assertThat(c).isNotEmpty());
    }

    @Test
    @WithMockUser(username = "recepcion", roles = "RECEPCIONISTA")
    void receptionistCreatesPatientsButNotDentists() {
        String ciNit = "PAC-" + unique();
        graphQl.document(CREAR_PACIENTE).variable("input", pacienteInput(ciNit)).execute()
                .path("crearPaciente.ciNit").entity(String.class).isEqualTo(ciNit);

        String suffix = unique();
        graphQl.document(CREAR_ODONTOLOGO).variable("input", odontologoInput(suffix, List.of(ORTODONCIA_ID))).execute()
                .errors().expect(e -> e.getErrorType() == ErrorType.FORBIDDEN).verify();
        assertThat(count("odontologos", "matricula_profesional", "MAT-" + suffix)).isZero();
    }
}
