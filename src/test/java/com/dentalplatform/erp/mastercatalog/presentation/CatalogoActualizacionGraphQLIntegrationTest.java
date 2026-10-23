package com.dentalplatform.erp.mastercatalog.presentation;

import com.dentalplatform.erp.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.graphql.test.tester.GraphQlTester;
import org.springframework.security.test.context.support.WithMockUser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * S9: update and logical-deactivation operations of the master catalogs. Every rejected operation asserts that the
 * row, the audit trail and the outbox are byte-for-byte unchanged.
 */
@WithMockUser(username = "admin", roles = "ADMIN")
class CatalogoActualizacionGraphQLIntegrationTest extends IntegrationTestBase {

    private static final String ORTODONCIA_ID = "40000000-0000-0000-0000-000000000002";
    private static final String ESTETICA_ID = "40000000-0000-0000-0000-000000000004";
    private static final String ENDODONCIA_ID = "40000000-0000-0000-0000-000000000003";
    private static final String SERVICIO_CONSULTA_ID = "70000000-0000-0000-0000-000000000001";

    private static final String CREAR_PACIENTE = """
            mutation($input: CrearPacienteInput!) { crearPaciente(input: $input) { id } }""";
    private static final String ACTUALIZAR_PACIENTE = """
            mutation($id: UUID!, $input: ActualizarPacienteInput!) {
              actualizarPaciente(id: $id, input: $input) {
                id ciNit nombres apellidos telefonoWhatsapp email direccion activo
              }
            }""";
    private static final String ELIMINAR_PACIENTE = "mutation($id: UUID!) { eliminarPaciente(id: $id) }";
    private static final String ACTUALIZAR_ODONTOLOGO = """
            mutation($id: UUID!, $input: ActualizarOdontologoInput!) {
              actualizarOdontologo(id: $id, input: $input) {
                id ci matriculaProfesional nombres apellidos telefono email porcentajeComision activo
                especialidades { nombre }
              }
            }""";
    private static final String ELIMINAR_ODONTOLOGO = "mutation($id: UUID!) { eliminarOdontologo(id: $id) }";
    private static final String ACTUALIZAR_ESPECIALIDAD = """
            mutation($id: UUID!, $input: ActualizarEspecialidadInput!) {
              actualizarEspecialidad(id: $id, input: $input) { id codigo nombre descripcion activo }
            }""";
    private static final String ELIMINAR_ESPECIALIDAD = "mutation($id: UUID!) { eliminarEspecialidad(id: $id) }";
    private static final String ACTUALIZAR_CONSULTORIO = """
            mutation($id: UUID!, $input: ActualizarConsultorioInput!) {
              actualizarConsultorio(id: $id, input: $input) { id codigo nombre ubicacion estado }
            }""";
    private static final String ELIMINAR_CONSULTORIO = "mutation($id: UUID!) { eliminarConsultorio(id: $id) }";
    private static final String ACTUALIZAR_SERVICIO = """
            mutation($id: UUID!, $input: ActualizarServicioInput!) {
              actualizarServicioOdontologico(id: $id, input: $input) {
                id codigo nombre descripcion precioBaseBs activo especialidad { id }
              }
            }""";
    private static final String ELIMINAR_SERVICIO = "mutation($id: UUID!) { eliminarServicioOdontologico(id: $id) }";

    @Autowired
    private JsonMapper jsonMapper;

    private static String unique() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    // ---------------------------------------------------------------- helpers

    private record Estado(List<Map<String, Object>> fila, List<Map<String, Object>> especialidades, int audits,
                          List<Map<String, Object>> outbox) { }

    private Estado estado(String tabla, UUID id) {
        return new Estado(jdbc.queryForList("SELECT * FROM " + tabla + " WHERE id = ?", id),
                jdbc.queryForList("SELECT * FROM odontologo_especialidades WHERE odontologo_id = ? "
                        + "ORDER BY especialidad_id", id),
                jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class),
                jdbc.queryForList("SELECT * FROM catalog_event_outbox ORDER BY event_id"));
    }

    private int audits(String accion, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE accion = ? AND entidad_id = ?",
                Integer.class, accion, id);
    }

    private int events(String routingKey, String idField, UUID id) {
        return jdbc.queryForObject("SELECT count(*) FROM catalog_event_outbox WHERE routing_key = ? "
                + "AND payload->>? = ?", Integer.class, routingKey, idField, id.toString());
    }

    private int outboxTotal() {
        return jdbc.queryForObject("SELECT count(*) FROM catalog_event_outbox", Integer.class);
    }

    private JsonNode lastEvent(String routingKey, String idField, UUID id) {
        verifyNoInteractions(rabbitTemplate);
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT event_id, exchange_name, payload::text AS payload, attempts, sent_at
                FROM catalog_event_outbox WHERE routing_key = ? AND payload->>? = ?
                ORDER BY created_at DESC, event_id DESC LIMIT 1""", routingKey, idField, id.toString());
        JsonNode payload = jsonMapper.readTree(row.get("payload").toString());
        assertThat(payload.get("eventId").asString()).isEqualTo(row.get("event_id").toString());
        assertThat(row.get("exchange_name")).isEqualTo("dental.topic.exchange");
        assertThat(row.get("attempts")).isEqualTo(0);
        assertThat(row.get("sent_at")).isNull();
        return payload;
    }

    private static Set<String> fields(JsonNode node) {
        return Set.copyOf(node.propertyNames());
    }

    private void rejected(String document, Map<String, Object> variables, ErrorType type, String fragment) {
        GraphQlTester.Request<?> request = graphQl.document(document);
        for (Map.Entry<String, Object> v : variables.entrySet()) {
            request = request.variable(v.getKey(), v.getValue());
        }
        request.execute().errors().expect(e -> e.getErrorType() == type
                && (fragment == null || (e.getMessage() != null && e.getMessage().contains(fragment)))).verify();
    }

    private void assertRejectedLeavesNothingChanged(String tabla, UUID id, String document,
                                                    Map<String, Object> variables, ErrorType type, String fragment) {
        Estado antes = estado(tabla, id);
        rejected(document, variables, type, fragment);
        assertThat(estado(tabla, id)).isEqualTo(antes);
        verifyNoInteractions(rabbitTemplate);
    }

    private static Map<String, Object> vars(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    private UUID crearPaciente(String ciNit) {
        return UUID.fromString(graphQl.document(CREAR_PACIENTE).variable("input", Map.of("ciNit", ciNit,
                        "nombres", "Carlos Andrés", "apellidos", "Justiniano", "fechaNacimiento", "1995-04-12",
                        "telefonoWhatsapp", "+59170012345", "email", "c@gmail.com")).execute()
                .path("crearPaciente.id").entity(String.class).get());
    }

    private Map<String, Object> pacienteInput(String ciNit) {
        return Map.of("ciNit", ciNit, "nombres", "Ana María", "apellidos", "Rojas Vaca",
                "fechaNacimiento", "1990-01-31", "genero", "FEMENINO", "telefonoWhatsapp", "+59171111111",
                "email", "ana@gmail.com", "direccion", "Calle 5 #10");
    }

    private UUID crearOdontologo(String suffix, List<String> especialidades) {
        return UUID.fromString(graphQl.document("""
                        mutation($input: CrearOdontologoInput!) { crearOdontologo(input: $input) { id } }""")
                .variable("input", Map.of("ci", "CI-" + suffix, "matriculaProfesional", "mat-" + suffix,
                        "nombres", "Valeria", "apellidos", "Roca " + suffix, "telefono", "+59170811223",
                        "porcentajeComision", 35, "especialidadesIds", especialidades)).execute()
                .path("crearOdontologo.id").entity(String.class).get());
    }

    private Map<String, Object> odontologoInput(String suffix, List<String> especialidades) {
        return Map.of("ci", "CI2-" + suffix, "matriculaProfesional", "mat2-" + suffix, "nombres", "Valeria Inés",
                "apellidos", "Roca Nuevo", "telefono", "+59170000000", "email", "vi@clinica.bo",
                "porcentajeComision", 42.5, "especialidadesIds", especialidades);
    }

    private UUID crearEspecialidad(String codigo) {
        return UUID.fromString(graphQl.document("""
                        mutation($c: String!, $n: String!) { crearEspecialidad(codigo: $c, nombre: $n) { id } }""")
                .variable("c", codigo).variable("n", "Esp " + codigo).execute()
                .path("crearEspecialidad.id").entity(String.class).get());
    }

    private UUID crearConsultorio(String codigo) {
        return UUID.fromString(graphQl.document("""
                        mutation($input: CrearConsultorioInput!) { crearConsultorio(input: $input) { id } }""")
                .variable("input", Map.of("codigo", codigo, "nombre", "Consultorio " + codigo)).execute()
                .path("crearConsultorio.id").entity(String.class).get());
    }

    private UUID crearServicio(String codigo, Object especialidadId, String precio) {
        Map<String, Object> input = new HashMap<>(Map.of("codigo", codigo, "nombre", "Servicio " + codigo,
                "precioBaseBs", new java.math.BigDecimal(precio)));
        input.put("especialidadId", especialidadId);
        return UUID.fromString(graphQl.document("""
                        mutation($input: CrearServicioInput!) { crearServicioOdontologico(input: $input) { id } }""")
                .variable("input", input).execute().path("crearServicioOdontologico.id").entity(String.class).get());
    }

    private UUID crearOrden(UUID pacienteId, String servicioId, int cantidad) {
        return UUID.fromString(graphQl.document("""
                        mutation($input: CrearOrdenCobroInput!) { crearOrdenCobroManual(input: $input) { id } }""")
                .variable("input", Map.of("pacienteId", pacienteId.toString(), "items",
                        List.of(Map.of("servicioId", servicioId, "cantidad", cantidad)))).execute()
                .path("crearOrdenCobroManual.id").entity(String.class).get());
    }

    // ---------------------------------------------------------------- actualizarPaciente

    @Test
    void updatePatientReplacesFieldsAuditsAndPublishesActualizadoWithActivo() {
        String ciNit = "PAC-" + unique();
        UUID id = crearPaciente(ciNit);
        String nuevoCi = ciNit + "-B";
        int creados = events("catalogo.paciente.creado", "pacienteId", id);

        graphQl.document(ACTUALIZAR_PACIENTE).variable("id", id).variable("input", pacienteInput(nuevoCi)).execute()
                .path("actualizarPaciente.id").entity(String.class).isEqualTo(id.toString())
                .path("actualizarPaciente.ciNit").entity(String.class).isEqualTo(nuevoCi)
                .path("actualizarPaciente.nombres").entity(String.class).isEqualTo("Ana María")
                .path("actualizarPaciente.email").entity(String.class).isEqualTo("ana@gmail.com")
                .path("actualizarPaciente.direccion").entity(String.class).isEqualTo("Calle 5 #10")
                .path("actualizarPaciente.activo").entity(Boolean.class).isEqualTo(true);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM pacientes WHERE id = ?", id);
        assertThat(row).containsEntry("ci_nit", nuevoCi).containsEntry("genero", "FEMENINO")
                .containsEntry("telefono_whatsapp", "+59171111111").containsEntry("activo", true);
        assertThat(audits("PACIENTE_ACTUALIZADO", id)).isEqualTo(1);
        assertThat(events("catalogo.paciente.creado", "pacienteId", id)).isEqualTo(creados);
        assertThat(events("catalogo.paciente.actualizado", "pacienteId", id)).isEqualTo(1);
        JsonNode event = lastEvent("catalogo.paciente.actualizado", "pacienteId", id);
        assertThat(fields(event)).containsExactlyInAnyOrder("eventId", "pacienteId", "ciNit", "nombreCompleto",
                "telefonoWhatsapp", "fechaNacimiento", "activo");
        assertThat(event.get("ciNit").asString()).isEqualTo(nuevoCi);
        assertThat(event.get("nombreCompleto").asString()).isEqualTo("Ana María Rojas Vaca");
        assertThat(event.get("telefonoWhatsapp").asString()).isEqualTo("+59171111111");
        assertThat(event.get("fechaNacimiento").asString()).isEqualTo("1990-01-31");
        assertThat(event.get("activo").asBoolean()).isTrue();

        // keeping the same CI/NIT is not a conflict with itself, and each update carries a fresh eventId
        graphQl.document(ACTUALIZAR_PACIENTE).variable("id", id).variable("input", pacienteInput(nuevoCi)).execute()
                .path("actualizarPaciente.ciNit").entity(String.class).isEqualTo(nuevoCi);
        assertThat(events("catalogo.paciente.actualizado", "pacienteId", id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT event_id) FROM catalog_event_outbox "
                + "WHERE routing_key = 'catalogo.paciente.actualizado' AND payload->>'pacienteId' = ?",
                Integer.class, id.toString())).isEqualTo(2);
    }

    @Test
    void updatePatientWithAnotherPatientsCiNitIsBadRequestAndChangesNothing() {
        String ocupado = "PAC-" + unique();
        crearPaciente(ocupado);
        UUID id = crearPaciente("PAC-" + unique());

        assertRejectedLeavesNothingChanged("pacientes", id, ACTUALIZAR_PACIENTE,
                vars("id", id, "input", pacienteInput(" " + ocupado + " ")), ErrorType.BAD_REQUEST, ocupado);
    }

    @Test
    void updatePatientWithInvalidDataIsBadRequestAndChangesNothing() {
        UUID id = crearPaciente("PAC-" + unique());
        Map<String, Object> invalido = new HashMap<>(pacienteInput("PAC-" + unique()));
        invalido.put("email", "no-es-email");

        assertRejectedLeavesNothingChanged("pacientes", id, ACTUALIZAR_PACIENTE,
                vars("id", id, "input", invalido), ErrorType.BAD_REQUEST, "El email no es válido");
    }

    @Test
    void updateUnknownPatientIsNotFoundWithoutAuditOrEvent() {
        UUID id = UUID.randomUUID();
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        int outbox = outboxTotal();

        rejected(ACTUALIZAR_PACIENTE, vars("id", id, "input", pacienteInput("PAC-" + unique())),
                ErrorType.NOT_FOUND, id.toString());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(audits);
        assertThat(outboxTotal()).isEqualTo(outbox);
    }

    @Test
    @WithMockUser(username = "caja", roles = "CAJERO")
    void cashierCannotUpdateOrDeactivatePatients() {
        UUID id = jdbc.queryForObject("SELECT id FROM pacientes ORDER BY ci_nit LIMIT 1", UUID.class);

        assertRejectedLeavesNothingChanged("pacientes", id, ACTUALIZAR_PACIENTE,
                vars("id", id, "input", pacienteInput("PAC-" + unique())), ErrorType.FORBIDDEN, null);
        assertRejectedLeavesNothingChanged("pacientes", id, ELIMINAR_PACIENTE, vars("id", id),
                ErrorType.FORBIDDEN, null);
    }

    @Test
    @WithMockUser(username = "recepcion", roles = "RECEPCIONISTA")
    void receptionistUpdatesAndDeactivatesPatientsLikeItCreatesThem() {
        UUID id = crearPaciente("PAC-" + unique());

        graphQl.document(ACTUALIZAR_PACIENTE).variable("id", id).variable("input", pacienteInput("PAC-" + unique()))
                .execute().path("actualizarPaciente.nombres").entity(String.class).isEqualTo("Ana María");
        graphQl.document(ELIMINAR_PACIENTE).variable("id", id).execute()
                .path("eliminarPaciente").entity(Boolean.class).isEqualTo(true);

        assertThat(jdbc.queryForObject("SELECT activo FROM pacientes WHERE id = ?", Boolean.class, id)).isFalse();
    }

    // ---------------------------------------------------------------- eliminarPaciente

    @Test
    void deactivatePatientIsLogicalAuditedPublishedAndHiddenFromTheDefaultListing() {
        String ciNit = "PAC-" + unique();
        UUID id = crearPaciente(ciNit);
        int rows = jdbc.queryForObject("SELECT count(*) FROM pacientes", Integer.class);

        graphQl.document(ELIMINAR_PACIENTE).variable("id", id).execute()
                .path("eliminarPaciente").entity(Boolean.class).isEqualTo(true);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM pacientes", Integer.class)).isEqualTo(rows);
        assertThat(jdbc.queryForObject("SELECT activo FROM pacientes WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(audits("PACIENTE_DESACTIVADO", id)).isEqualTo(1);
        JsonNode event = lastEvent("catalogo.paciente.actualizado", "pacienteId", id);
        assertThat(fields(event)).containsExactlyInAnyOrder("eventId", "pacienteId", "ciNit", "nombreCompleto",
                "telefonoWhatsapp", "fechaNacimiento", "activo");
        assertThat(event.get("activo").asBoolean()).isFalse();
        assertThat(event.get("ciNit").asString()).isEqualTo(ciNit);

        // listing semantics are unchanged: only active patients; lookup by id still resolves the inactive one
        graphQl.document("query($b: String) { listarPacientes(busqueda: $b) { id } }").variable("b", ciNit)
                .execute().path("listarPacientes").entityList(Object.class).hasSize(0);
        graphQl.document("query($id: UUID!) { obtenerPacientePorId(id: $id) { id activo } }").variable("id", id)
                .execute().path("obtenerPacientePorId.activo").entity(Boolean.class).isEqualTo(false);
    }

    @Test
    void deactivatingAnInactivePatientIsIdempotentWithoutNewAuditOrEvent() {
        UUID id = crearPaciente("PAC-" + unique());
        graphQl.document(ELIMINAR_PACIENTE).variable("id", id).execute().path("eliminarPaciente").hasValue();
        Estado antes = estado("pacientes", id);

        graphQl.document(ELIMINAR_PACIENTE).variable("id", id).execute()
                .path("eliminarPaciente").entity(Boolean.class).isEqualTo(true);

        assertThat(estado("pacientes", id)).isEqualTo(antes);
        assertThat(audits("PACIENTE_DESACTIVADO", id)).isEqualTo(1);
        assertThat(events("catalogo.paciente.actualizado", "pacienteId", id)).isEqualTo(1);
    }

    @Test
    void deactivatePatientWithPendingOrPartialOrdersIsBadRequestUntilTheyArePaid() {
        UUID id = crearPaciente("PAC-" + unique());
        UUID orden = crearOrden(id, SERVICIO_CONSULTA_ID, 1);

        assertRejectedLeavesNothingChanged("pacientes", id, ELIMINAR_PACIENTE, vars("id", id),
                ErrorType.BAD_REQUEST, "PENDIENTE o PARCIAL");
        jdbc.update("UPDATE ordenes_cobro SET estado = 'PARCIAL' WHERE id = ?", orden);
        assertRejectedLeavesNothingChanged("pacientes", id, ELIMINAR_PACIENTE, vars("id", id),
                ErrorType.BAD_REQUEST, "PENDIENTE o PARCIAL");
        assertThat(jdbc.queryForObject("SELECT activo FROM pacientes WHERE id = ?", Boolean.class, id)).isTrue();

        jdbc.update("UPDATE ordenes_cobro SET estado = 'PAGADO' WHERE id = ?", orden);
        graphQl.document(ELIMINAR_PACIENTE).variable("id", id).execute()
                .path("eliminarPaciente").entity(Boolean.class).isEqualTo(true);
        assertThat(jdbc.queryForObject("SELECT activo FROM pacientes WHERE id = ?", Boolean.class, id)).isFalse();
    }

    @Test
    void deactivateUnknownPatientIsNotFound() {
        int audits = jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class);
        int outbox = outboxTotal();

        rejected(ELIMINAR_PACIENTE, vars("id", UUID.randomUUID()), ErrorType.NOT_FOUND, null);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs", Integer.class)).isEqualTo(audits);
        assertThat(outboxTotal()).isEqualTo(outbox);
    }

    // ---------------------------------------------------------------- odontólogo

    @Test
    void updateDentistReplacesSpecialtiesAndPublishesActualizadoWithActivo() {
        String suffix = unique();
        UUID id = crearOdontologo(suffix, List.of(ORTODONCIA_ID));

        graphQl.document(ACTUALIZAR_ODONTOLOGO).variable("id", id)
                .variable("input", odontologoInput(suffix, List.of(ESTETICA_ID, ENDODONCIA_ID))).execute()
                .path("actualizarOdontologo.matriculaProfesional").entity(String.class).isEqualTo("MAT2-" + suffix)
                .path("actualizarOdontologo.porcentajeComision").entity(Double.class).isEqualTo(42.5)
                .path("actualizarOdontologo.activo").entity(Boolean.class).isEqualTo(true)
                .path("actualizarOdontologo.especialidades[*].nombre").entityList(String.class)
                .satisfies(n -> assertThat(n).containsExactlyInAnyOrder("Estética Dental", "Endodoncia"));

        assertThat(jdbc.queryForList("SELECT especialidad_id::text FROM odontologo_especialidades "
                + "WHERE odontologo_id = ?", String.class, id)).containsExactlyInAnyOrder(ESTETICA_ID, ENDODONCIA_ID);
        assertThat(audits("ODONTOLOGO_ACTUALIZADO", id)).isEqualTo(1);
        JsonNode event = lastEvent("catalogo.odontologo.actualizado", "odontologoId", id);
        assertThat(fields(event)).containsExactlyInAnyOrder("eventId", "odontologoId", "nombreCompleto",
                "matriculaProfesional", "especialidades", "activo");
        assertThat(event.get("nombreCompleto").asString()).isEqualTo("Valeria Inés Roca Nuevo");
        assertThat(event.get("matriculaProfesional").asString()).isEqualTo("MAT2-" + suffix);
        assertThat(event.get("especialidades").valueStream().map(JsonNode::asString).toList())
                .containsExactlyInAnyOrder("Estética Dental", "Endodoncia");
        assertThat(event.get("activo").asBoolean()).isTrue();
    }

    @Test
    void updateDentistWithDuplicateCiOrMatriculaOrUnknownSpecialtyChangesNothing() {
        String otro = unique();
        crearOdontologo(otro, List.of(ORTODONCIA_ID));
        String suffix = unique();
        UUID id = crearOdontologo(suffix, List.of(ORTODONCIA_ID));

        Map<String, Object> ciDuplicado = new HashMap<>(odontologoInput(suffix, List.of(ESTETICA_ID)));
        ciDuplicado.put("ci", "CI-" + otro);
        assertRejectedLeavesNothingChanged("odontologos", id, ACTUALIZAR_ODONTOLOGO,
                vars("id", id, "input", ciDuplicado), ErrorType.BAD_REQUEST, "CI-" + otro);

        Map<String, Object> matriculaDuplicada = new HashMap<>(odontologoInput(suffix, List.of(ESTETICA_ID)));
        matriculaDuplicada.put("matriculaProfesional", " mat-" + otro);
        assertRejectedLeavesNothingChanged("odontologos", id, ACTUALIZAR_ODONTOLOGO,
                vars("id", id, "input", matriculaDuplicada), ErrorType.BAD_REQUEST, "MAT-" + otro);

        String inexistente = UUID.randomUUID().toString();
        assertRejectedLeavesNothingChanged("odontologos", id, ACTUALIZAR_ODONTOLOGO,
                vars("id", id, "input", odontologoInput(suffix, List.of(ESTETICA_ID, inexistente))),
                ErrorType.BAD_REQUEST, inexistente);

        // keeping its own CI and matrícula is allowed
        Map<String, Object> propios = new HashMap<>(odontologoInput(suffix, List.of(ORTODONCIA_ID)));
        propios.put("ci", "CI-" + suffix);
        propios.put("matriculaProfesional", "mat-" + suffix);
        graphQl.document(ACTUALIZAR_ODONTOLOGO).variable("id", id).variable("input", propios).execute()
                .path("actualizarOdontologo.ci").entity(String.class).isEqualTo("CI-" + suffix);
    }

    @Test
    void updateUnknownDentistIsNotFoundAndReceptionistIsForbidden() {
        UUID id = UUID.randomUUID();
        int outbox = outboxTotal();
        rejected(ACTUALIZAR_ODONTOLOGO, vars("id", id, "input", odontologoInput(unique(), List.of())),
                ErrorType.NOT_FOUND, id.toString());
        assertThat(outboxTotal()).isEqualTo(outbox);
    }

    @Test
    @WithMockUser(username = "recepcion", roles = "RECEPCIONISTA")
    void receptionistCannotUpdateOrDeactivateDentists() {
        UUID id = jdbc.queryForObject("SELECT id FROM odontologos ORDER BY ci LIMIT 1", UUID.class);

        assertRejectedLeavesNothingChanged("odontologos", id, ACTUALIZAR_ODONTOLOGO,
                vars("id", id, "input", odontologoInput(unique(), List.of())), ErrorType.FORBIDDEN, null);
        assertRejectedLeavesNothingChanged("odontologos", id, ELIMINAR_ODONTOLOGO, vars("id", id),
                ErrorType.FORBIDDEN, null);
    }

    @Test
    void deactivateDentistIsLogicalPublishedIdempotentAndListedOnlyWithSoloActivosFalse() {
        String suffix = unique();
        UUID id = crearOdontologo(suffix, List.of(ORTODONCIA_ID));

        graphQl.document(ELIMINAR_ODONTOLOGO).variable("id", id).execute()
                .path("eliminarOdontologo").entity(Boolean.class).isEqualTo(true);

        assertThat(jdbc.queryForObject("SELECT activo FROM odontologos WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM odontologo_especialidades WHERE odontologo_id = ?",
                Integer.class, id)).isEqualTo(1);
        assertThat(audits("ODONTOLOGO_DESACTIVADO", id)).isEqualTo(1);
        JsonNode event = lastEvent("catalogo.odontologo.actualizado", "odontologoId", id);
        assertThat(event.get("activo").asBoolean()).isFalse();
        assertThat(event.get("matriculaProfesional").asString()).isEqualTo("MAT-" + suffix);

        String listado = "query($s: Boolean) { listarOdontologos(soloActivos: $s) { id activo } }";
        graphQl.document(listado).variable("s", true).execute()
                .path("listarOdontologos[?(@.id=='" + id + "')]").entityList(Object.class).hasSize(0);
        graphQl.document(listado).variable("s", false).execute()
                .path("listarOdontologos[?(@.id=='" + id + "')].activo").entityList(Boolean.class)
                .containsExactly(false);

        Estado antes = estado("odontologos", id);
        graphQl.document(ELIMINAR_ODONTOLOGO).variable("id", id).execute()
                .path("eliminarOdontologo").entity(Boolean.class).isEqualTo(true);
        assertThat(estado("odontologos", id)).isEqualTo(antes);
    }

    @Test
    void deactivateUnknownDentistIsNotFound() {
        int outbox = outboxTotal();
        rejected(ELIMINAR_ODONTOLOGO, vars("id", UUID.randomUUID()), ErrorType.NOT_FOUND, null);
        assertThat(outboxTotal()).isEqualTo(outbox);
    }

    // ---------------------------------------------------------------- especialidad

    @Test
    void updateSpecialtyPersistsAuditsAndPublishesNothing() {
        String codigo = "ESP-" + unique();
        UUID id = crearEspecialidad(codigo);
        int outbox = outboxTotal();

        graphQl.document(ACTUALIZAR_ESPECIALIDAD).variable("id", id)
                .variable("input", Map.of("codigo", codigo + "-b", "nombre", "Periodoncia", "descripcion", "Encías"))
                .execute()
                .path("actualizarEspecialidad.codigo").entity(String.class).isEqualTo(codigo + "-B")
                .path("actualizarEspecialidad.nombre").entity(String.class).isEqualTo("Periodoncia")
                .path("actualizarEspecialidad.descripcion").entity(String.class).isEqualTo("Encías")
                .path("actualizarEspecialidad.activo").entity(Boolean.class).isEqualTo(true);

        assertThat(jdbc.queryForObject("SELECT codigo FROM especialidades WHERE id = ?", String.class, id))
                .isEqualTo(codigo + "-B");
        assertThat(audits("ESPECIALIDAD_ACTUALIZADA", id)).isEqualTo(1);
        assertThat(outboxTotal()).isEqualTo(outbox);
    }

    @Test
    void updateSpecialtyWithDuplicateCodeIsBadRequestAndUnknownIsNotFound() {
        String ocupado = "ESP-" + unique();
        crearEspecialidad(ocupado);
        UUID id = crearEspecialidad("ESP-" + unique());

        assertRejectedLeavesNothingChanged("especialidades", id, ACTUALIZAR_ESPECIALIDAD,
                vars("id", id, "input", Map.of("codigo", ocupado.toLowerCase(), "nombre", "X")),
                ErrorType.BAD_REQUEST, ocupado);
        UUID inexistente = UUID.randomUUID();
        rejected(ACTUALIZAR_ESPECIALIDAD, vars("id", inexistente, "input", Map.of("codigo", "Z", "nombre", "X")),
                ErrorType.NOT_FOUND, inexistente.toString());
    }

    @Test
    @WithMockUser(username = "recepcion", roles = "RECEPCIONISTA")
    void receptionistCannotUpdateOrDeactivateSpecialties() {
        UUID id = UUID.fromString(ORTODONCIA_ID);

        assertRejectedLeavesNothingChanged("especialidades", id, ACTUALIZAR_ESPECIALIDAD,
                vars("id", id, "input", Map.of("codigo", "ORTO-X", "nombre", "X")), ErrorType.FORBIDDEN, null);
        assertRejectedLeavesNothingChanged("especialidades", id, ELIMINAR_ESPECIALIDAD, vars("id", id),
                ErrorType.FORBIDDEN, null);
    }

    @Test
    void deactivateSpecialtyIsBlockedByActiveServicesAndActiveDentistsThenSucceedsAndIsIdempotent() {
        String codigo = "ESP-" + unique();
        UUID id = crearEspecialidad(codigo);
        UUID servicio = crearServicio("SRV-" + unique(), id, "50");
        UUID odontologo = crearOdontologo(unique(), List.of(id.toString()));

        assertRejectedLeavesNothingChanged("especialidades", id, ELIMINAR_ESPECIALIDAD, vars("id", id),
                ErrorType.BAD_REQUEST, "1 servicio(s) activo(s) y 1 odontólogo(s) activo(s)");

        graphQl.document(ELIMINAR_SERVICIO).variable("id", servicio).execute().path("eliminarServicioOdontologico")
                .entity(Boolean.class).isEqualTo(true);
        assertRejectedLeavesNothingChanged("especialidades", id, ELIMINAR_ESPECIALIDAD, vars("id", id),
                ErrorType.BAD_REQUEST, "1 odontólogo(s) activo(s)");

        graphQl.document(ELIMINAR_ODONTOLOGO).variable("id", odontologo).execute().path("eliminarOdontologo")
                .entity(Boolean.class).isEqualTo(true);
        int outbox = outboxTotal();
        graphQl.document(ELIMINAR_ESPECIALIDAD).variable("id", id).execute()
                .path("eliminarEspecialidad").entity(Boolean.class).isEqualTo(true);

        assertThat(jdbc.queryForObject("SELECT activo FROM especialidades WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(audits("ESPECIALIDAD_DESACTIVADA", id)).isEqualTo(1);
        assertThat(outboxTotal()).isEqualTo(outbox);
        graphQl.document("{ listarEspecialidades { id } }").execute()
                .path("listarEspecialidades[?(@.id=='" + id + "')]").entityList(Object.class).hasSize(0);

        Estado antes = estado("especialidades", id);
        graphQl.document(ELIMINAR_ESPECIALIDAD).variable("id", id).execute()
                .path("eliminarEspecialidad").entity(Boolean.class).isEqualTo(true);
        assertThat(estado("especialidades", id)).isEqualTo(antes);
    }

    @Test
    void deactivateUnknownSpecialtyIsNotFound() {
        rejected(ELIMINAR_ESPECIALIDAD, vars("id", UUID.randomUUID()), ErrorType.NOT_FOUND, null);
    }

    // ---------------------------------------------------------------- consultorio

    @Test
    void updateOfficeValidatesEstadoPublishesActualizadoAndAudits() {
        String codigo = "CON-" + unique();
        UUID id = crearConsultorio(codigo);

        graphQl.document(ACTUALIZAR_CONSULTORIO).variable("id", id).variable("input",
                        Map.of("codigo", codigo + "-b", "nombre", "Sala nueva", "ubicacion", "Piso 3",
                                "estado", "mantenimiento")).execute()
                .path("actualizarConsultorio.codigo").entity(String.class).isEqualTo(codigo + "-B")
                .path("actualizarConsultorio.estado").entity(String.class).isEqualTo("MANTENIMIENTO")
                .path("actualizarConsultorio.ubicacion").entity(String.class).isEqualTo("Piso 3");

        assertThat(audits("CONSULTORIO_ACTUALIZADO", id)).isEqualTo(1);
        JsonNode event = lastEvent("catalogo.consultorio.actualizado", "consultorioId", id);
        assertThat(fields(event)).containsExactlyInAnyOrder("eventId", "consultorioId", "codigo", "nombre", "estado");
        assertThat(event.get("codigo").asString()).isEqualTo(codigo + "-B");
        assertThat(event.get("nombre").asString()).isEqualTo("Sala nueva");
        assertThat(event.get("estado").asString()).isEqualTo("MANTENIMIENTO");

        // omitting estado keeps the current one
        graphQl.document(ACTUALIZAR_CONSULTORIO).variable("id", id).variable("input",
                        Map.of("codigo", codigo + "-b", "nombre", "Sala nueva")).execute()
                .path("actualizarConsultorio.estado").entity(String.class).isEqualTo("MANTENIMIENTO");
    }

    @Test
    void updateOfficeWithUnknownEstadoOrDuplicateCodeIsBadRequestAndChangesNothing() {
        String ocupado = "CON-" + unique();
        crearConsultorio(ocupado);
        UUID id = crearConsultorio("CON-" + unique());

        assertRejectedLeavesNothingChanged("consultorios", id, ACTUALIZAR_CONSULTORIO,
                vars("id", id, "input", Map.of("codigo", "CON-OK-" + unique(), "nombre", "X", "estado", "OCUPADO")),
                ErrorType.BAD_REQUEST, "Valores permitidos: DISPONIBLE, MANTENIMIENTO, INACTIVO");
        assertRejectedLeavesNothingChanged("consultorios", id, ACTUALIZAR_CONSULTORIO,
                vars("id", id, "input", Map.of("codigo", ocupado, "nombre", "X")), ErrorType.BAD_REQUEST, ocupado);
        UUID inexistente = UUID.randomUUID();
        rejected(ACTUALIZAR_CONSULTORIO, vars("id", inexistente, "input", Map.of("codigo", "Z", "nombre", "X")),
                ErrorType.NOT_FOUND, inexistente.toString());
    }

    @Test
    @WithMockUser(username = "recepcion", roles = "RECEPCIONISTA")
    void receptionistCannotUpdateOrDeactivateOffices() {
        UUID id = UUID.fromString("30000000-0000-0000-0000-000000000001");

        assertRejectedLeavesNothingChanged("consultorios", id, ACTUALIZAR_CONSULTORIO,
                vars("id", id, "input", Map.of("codigo", "CONS-01", "nombre", "X")), ErrorType.FORBIDDEN, null);
        assertRejectedLeavesNothingChanged("consultorios", id, ELIMINAR_CONSULTORIO, vars("id", id),
                ErrorType.FORBIDDEN, null);
    }

    @Test
    void deactivateOfficeSetsEstadoInactivoPublishesIsIdempotentAndStaysListed() {
        String codigo = "CON-" + unique();
        UUID id = crearConsultorio(codigo);

        graphQl.document(ELIMINAR_CONSULTORIO).variable("id", id).execute()
                .path("eliminarConsultorio").entity(Boolean.class).isEqualTo(true);

        assertThat(jdbc.queryForObject("SELECT estado FROM consultorios WHERE id = ?", String.class, id))
                .isEqualTo("INACTIVO");
        assertThat(audits("CONSULTORIO_DESACTIVADO", id)).isEqualTo(1);
        JsonNode event = lastEvent("catalogo.consultorio.actualizado", "consultorioId", id);
        assertThat(event.get("estado").asString()).isEqualTo("INACTIVO");
        assertThat(event.get("codigo").asString()).isEqualTo(codigo);
        graphQl.document("{ listarConsultorios { id estado } }").execute()
                .path("listarConsultorios[?(@.id=='" + id + "')].estado").entityList(String.class)
                .containsExactly("INACTIVO");

        Estado antes = estado("consultorios", id);
        graphQl.document(ELIMINAR_CONSULTORIO).variable("id", id).execute()
                .path("eliminarConsultorio").entity(Boolean.class).isEqualTo(true);
        assertThat(estado("consultorios", id)).isEqualTo(antes);
        assertThat(events("catalogo.consultorio.actualizado", "consultorioId", id)).isEqualTo(1);
    }

    @Test
    void deactivateUnknownOfficeIsNotFound() {
        rejected(ELIMINAR_CONSULTORIO, vars("id", UUID.randomUUID()), ErrorType.NOT_FOUND, null);
    }

    // ---------------------------------------------------------------- servicio

    @Test
    void updateServiceChangesPriceAndSpecialtyAuditsAndPublishesNothing() {
        String codigo = "SRV-" + unique();
        UUID id = crearServicio(codigo, ORTODONCIA_ID, "100");
        int outbox = outboxTotal();

        graphQl.document(ACTUALIZAR_SERVICIO).variable("id", id).variable("input", Map.of(
                        "especialidadId", ESTETICA_ID, "codigo", codigo + "-b", "nombre", "Nuevo nombre",
                        "descripcion", "Detalle", "precioBaseBs", new java.math.BigDecimal("175.50"))).execute()
                .path("actualizarServicioOdontologico.codigo").entity(String.class).isEqualTo(codigo + "-B")
                .path("actualizarServicioOdontologico.precioBaseBs").entity(Double.class).isEqualTo(175.5)
                .path("actualizarServicioOdontologico.especialidad.id").entity(String.class).isEqualTo(ESTETICA_ID)
                .path("actualizarServicioOdontologico.activo").entity(Boolean.class).isEqualTo(true);

        assertThat(jdbc.queryForObject("SELECT precio_base_bs FROM servicios_odontologicos WHERE id = ?",
                java.math.BigDecimal.class, id)).isEqualByComparingTo("175.50");
        assertThat(audits("SERVICIO_ODONTOLOGICO_ACTUALIZADO", id)).isEqualTo(1);
        assertThat(outboxTotal()).isEqualTo(outbox);
    }

    @Test
    void updateServiceRejectsDuplicateCodeUnknownSpecialtyNegativePriceAndUnknownId() {
        String ocupado = "SRV-" + unique();
        crearServicio(ocupado, null, "10");
        UUID id = crearServicio("SRV-" + unique(), ORTODONCIA_ID, "20");
        Map<String, Object> base = new HashMap<>(Map.of("codigo", "SRV-OK-" + unique(), "nombre", "N",
                "precioBaseBs", new java.math.BigDecimal("30")));

        Map<String, Object> duplicado = new HashMap<>(base);
        duplicado.put("codigo", ocupado);
        assertRejectedLeavesNothingChanged("servicios_odontologicos", id, ACTUALIZAR_SERVICIO,
                vars("id", id, "input", duplicado), ErrorType.BAD_REQUEST, ocupado);

        Map<String, Object> sinEspecialidad = new HashMap<>(base);
        sinEspecialidad.put("especialidadId", UUID.randomUUID().toString());
        assertRejectedLeavesNothingChanged("servicios_odontologicos", id, ACTUALIZAR_SERVICIO,
                vars("id", id, "input", sinEspecialidad), ErrorType.BAD_REQUEST, "No existe la especialidad");

        Map<String, Object> negativo = new HashMap<>(base);
        negativo.put("precioBaseBs", new java.math.BigDecimal("-1"));
        assertRejectedLeavesNothingChanged("servicios_odontologicos", id, ACTUALIZAR_SERVICIO,
                vars("id", id, "input", negativo), ErrorType.BAD_REQUEST, "El precio base no puede ser negativo");

        UUID inexistente = UUID.randomUUID();
        rejected(ACTUALIZAR_SERVICIO, vars("id", inexistente, "input", base), ErrorType.NOT_FOUND,
                inexistente.toString());
    }

    @Test
    @WithMockUser(username = "recepcion", roles = "RECEPCIONISTA")
    void receptionistCannotUpdateOrDeactivateServices() {
        UUID id = UUID.fromString(SERVICIO_CONSULTA_ID);

        assertRejectedLeavesNothingChanged("servicios_odontologicos", id, ACTUALIZAR_SERVICIO,
                vars("id", id, "input", Map.of("codigo", "ODG-CONS", "nombre", "X", "precioBaseBs", 1)),
                ErrorType.FORBIDDEN, null);
        assertRejectedLeavesNothingChanged("servicios_odontologicos", id, ELIMINAR_SERVICIO, vars("id", id),
                ErrorType.FORBIDDEN, null);
    }

    @Test
    void changingAServicePriceNeverRewritesExistingOrderItems() {
        String codigo = "SRV-" + unique();
        UUID servicio = crearServicio(codigo, null, "100.00");
        UUID paciente = crearPaciente("PAC-" + unique());
        UUID orden = crearOrden(paciente, servicio.toString(), 2);
        List<Map<String, Object>> itemsAntes = jdbc.queryForList(
                "SELECT * FROM orden_cobro_items WHERE orden_cobro_id = ? ORDER BY id", orden);
        Map<String, Object> ordenAntes = jdbc.queryForMap("SELECT * FROM ordenes_cobro WHERE id = ?", orden);
        assertThat(itemsAntes).hasSize(1);
        assertThat((java.math.BigDecimal) itemsAntes.get(0).get("precio_unitario_bs")).isEqualByComparingTo("100");

        graphQl.document(ACTUALIZAR_SERVICIO).variable("id", servicio).variable("input", Map.of(
                        "codigo", codigo, "nombre", "Servicio " + codigo,
                        "precioBaseBs", new java.math.BigDecimal("999.99"))).execute()
                .path("actualizarServicioOdontologico.precioBaseBs").entity(Double.class).isEqualTo(999.99);

        assertThat(jdbc.queryForList("SELECT * FROM orden_cobro_items WHERE orden_cobro_id = ? ORDER BY id", orden))
                .isEqualTo(itemsAntes);
        assertThat(jdbc.queryForMap("SELECT * FROM ordenes_cobro WHERE id = ?", orden)).isEqualTo(ordenAntes);
        graphQl.document("query($id: UUID!) { obtenerOrdenCobroDetalle(id: $id) { montoTotalBs "
                        + "items { precioUnitarioBs subtotalBs servicio { precioBaseBs } } } }")
                .variable("id", orden).execute()
                .path("obtenerOrdenCobroDetalle.montoTotalBs").entity(Double.class).isEqualTo(200.0)
                .path("obtenerOrdenCobroDetalle.items[0].precioUnitarioBs").entity(Double.class).isEqualTo(100.0)
                .path("obtenerOrdenCobroDetalle.items[0].servicio.precioBaseBs").entity(Double.class)
                .isEqualTo(999.99);
    }

    @Test
    void deactivateServiceIsLogicalIdempotentAndHiddenFromTheListing() {
        String codigo = "SRV-" + unique();
        UUID id = crearServicio(codigo, ORTODONCIA_ID, "10");
        int outbox = outboxTotal();

        graphQl.document(ELIMINAR_SERVICIO).variable("id", id).execute()
                .path("eliminarServicioOdontologico").entity(Boolean.class).isEqualTo(true);

        assertThat(jdbc.queryForObject("SELECT activo FROM servicios_odontologicos WHERE id = ?", Boolean.class, id))
                .isFalse();
        assertThat(audits("SERVICIO_ODONTOLOGICO_DESACTIVADO", id)).isEqualTo(1);
        assertThat(outboxTotal()).isEqualTo(outbox);
        graphQl.document("{ listarServiciosOdontologicos { id } }").execute()
                .path("listarServiciosOdontologicos[?(@.id=='" + id + "')]").entityList(Object.class).hasSize(0);

        Estado antes = estado("servicios_odontologicos", id);
        graphQl.document(ELIMINAR_SERVICIO).variable("id", id).execute()
                .path("eliminarServicioOdontologico").entity(Boolean.class).isEqualTo(true);
        assertThat(estado("servicios_odontologicos", id)).isEqualTo(antes);
    }

    @Test
    void deactivateUnknownServiceIsNotFound() {
        rejected(ELIMINAR_SERVICIO, vars("id", UUID.randomUUID()), ErrorType.NOT_FOUND, null);
    }

    // ---------------------------------------------------------------- active flag on read types

    @Test
    void catalogTypesExposeActivoWithTheStoredValue() {
        graphQl.document("""
                        { listarPacientes { activo } listarOdontologos(soloActivos: false) { activo }
                          listarEspecialidades { activo } listarServiciosOdontologicos { activo } }""")
                .execute()
                .path("listarPacientes[*].activo").entityList(Boolean.class)
                .satisfies(v -> assertThat(v).isNotEmpty().containsOnly(true))
                .path("listarEspecialidades[*].activo").entityList(Boolean.class)
                .satisfies(v -> assertThat(v).isNotEmpty().containsOnly(true))
                .path("listarServiciosOdontologicos[*].activo").entityList(Boolean.class)
                .satisfies(v -> assertThat(v).isNotEmpty().containsOnly(true))
                .path("listarOdontologos[*].activo").entityList(Boolean.class)
                .satisfies(v -> assertThat(v).isNotEmpty());
    }
}
