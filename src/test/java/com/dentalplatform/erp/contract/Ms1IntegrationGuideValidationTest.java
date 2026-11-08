package com.dentalplatform.erp.contract;

import com.dentalplatform.erp.financebilling.infrastructure.messaging.ConsultaFinalizadaPayload;
import com.dentalplatform.erp.support.IntegrationTestBase;
import graphql.GraphQLContext;
import graphql.execution.RawVariables;
import graphql.execution.ValuesResolver;
import graphql.language.Field;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.GraphQlSource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates documentation without executing any GraphQL operations or publishing messages. */
class Ms1IntegrationGuideValidationTest extends IntegrationTestBase {

    private static final Path GUIDE = Path.of("docs/ms1-integration.md");
    private static final Pattern EXAMPLE = Pattern.compile(
            "```graphql\\n(.*?)\\n```\\n\\nVariables:\\n\\n```json\\n(.*?)\\n```", Pattern.DOTALL);
    private static final List<String> OPERATION_NAMES = List.of("ListarPacientesMs1", "DetallePacienteMs1",
            "CrearPacienteMs1", "ActualizarPacienteMs1", "EliminarPacienteMs1", "CatalogosMs1",
            "FinanzasPacienteMs1", "VincularUsuario", "MiUsuarioErp", "CrearUsuarioConIdentidad");

    @Autowired
    private GraphQlSource graphQlSource;

    @Autowired
    private JsonMapper json;

    @Test
    void everyGraphQlExampleAndItsVariablesMatchTheRuntimeSchema() throws Exception {
        String guide = Files.readString(GUIDE).replace("\r\n", "\n");
        assertThat(guide.split("```graphql\\n", -1)).hasSize(OPERATION_NAMES.size() + 1);
        var examples = EXAMPLE.matcher(guide);
        List<String> names = new ArrayList<>();
        List<String> rootFields = new ArrayList<>();
        while (examples.find()) {
            var document = Parser.parse(examples.group(1));
            assertThat(new Validator().validateDocument(graphQlSource.schema(), document, Locale.ENGLISH))
                    .as("schema validation for %s", examples.group(1)).isEmpty();
            var operations = document.getDefinitionsOfType(OperationDefinition.class);
            assertThat(operations).hasSize(1);
            var operation = operations.getFirst();
            names.add(operation.getName());
            var fields = operation.getSelectionSet().getSelectionsOfType(Field.class);
            rootFields.addAll(fields.stream().map(Field::getName).toList());
            if (operation.getName().equals("DetallePacienteMs1")) {
                assertThat(fields.getFirst().getSelectionSet().getSelectionsOfType(Field.class)
                        .stream().map(Field::getName).toList()).containsExactly("id", "ciNit", "nombres",
                        "apellidos", "fechaNacimiento", "genero", "telefonoWhatsapp", "email", "direccion", "activo");
            }
            Map<String, Object> variables = json.readValue(examples.group(2), new TypeReference<>() { });
            ValuesResolver.coerceVariableValues(graphQlSource.schema(), operation.getVariableDefinitions(),
                    RawVariables.of(variables), GraphQLContext.getDefault(), Locale.ENGLISH);
        }
        assertThat(names).containsExactlyElementsOf(OPERATION_NAMES);
        assertThat(rootFields).containsExactly("listarPacientes", "obtenerPacientePorId", "crearPaciente",
                "actualizarPaciente", "eliminarPaciente", "listarOdontologos", "listarEspecialidades",
                "listarConsultorios", "listarServiciosOdontologicos", "listarOrdenesCobro",
                "listarCuotasPendientesPorPaciente", "vincularIdentidadUsuario", "obtenerMiUsuarioId",
                "crearUsuarioConIdentidad");
    }

    @Test
    void jsonExamplesAreValidAndClinicalMessageMatchesTheFlatDto() throws Exception {
        String guide = Files.readString(GUIDE).replace("\r\n", "\n");
        var blocks = Pattern.compile("```json\\n(.*?)\\n```", Pattern.DOTALL).matcher(guide);
        int count = 0;
        int clinicalMessages = 0;
        while (blocks.find()) {
            count++;
            Map<String, Object> value = json.readValue(blocks.group(1), new TypeReference<>() { });
            if (value.containsKey("consultaId")) {
                clinicalMessages++;
                assertThat(value.keySet()).containsExactlyInAnyOrderElementsOf(
                        recordFields(ConsultaFinalizadaPayload.class));
                var payload = json.readValue(blocks.group(1), ConsultaFinalizadaPayload.class);
                assertThat(payload.consultaId()).isNotNull();
                assertThat(payload.serviciosRealizados()).hasSize(1);
                @SuppressWarnings("unchecked")
                var items = (List<Map<String, Object>>) value.get("serviciosRealizados");
                assertThat(items.getFirst().keySet()).containsExactlyInAnyOrderElementsOf(
                        recordFields(ConsultaFinalizadaPayload.ServicioRealizado.class));
                assertThat(payload.serviciosRealizados().getFirst().cantidad()).isEqualTo(1);
                assertThat(payload.serviciosRealizados().getFirst().precioUnitarioBs()).isEqualByComparingTo("150.00");
            }
        }
        assertThat(count).isEqualTo(OPERATION_NAMES.size() + 1);
        assertThat(clinicalMessages).isEqualTo(1);
    }

    private static List<String> recordFields(Class<?> recordType) {
        return Arrays.stream(recordType.getRecordComponents()).map(component -> component.getName()).toList();
    }
}
