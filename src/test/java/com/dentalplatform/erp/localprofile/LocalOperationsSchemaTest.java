package com.dentalplatform.erp.localprofile;

import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaParser;
import graphql.schema.idl.UnExecutableSchemaGenerator;
import graphql.validation.Validator;
import org.junit.jupiter.api.Test;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps docs/local-integration-data.md honest: every operation in operations.graphql validates against the real
 * schema.graphqls, its variables cover the declared ones, and the doc embeds the file verbatim.
 */
class LocalOperationsSchemaTest {

    private static final Path SCHEMA = Path.of("src/main/resources/graphql/schema.graphqls");
    private static final Path OPERATIONS = Path.of("src/test/resources/local-integration/operations.graphql");
    private static final Path DOC = Path.of("docs/local-integration-data.md");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Pattern LOCAL_UUID = Pattern.compile("\"(a[0-9a-b]000000-[^\"]+)\"");

    private record Operation(String name, String document, String variablesJson) { }

    @Test
    void everyDocumentedOperationIsValidAgainstTheRealSchema() throws IOException {
        GraphQLSchema schema = UnExecutableSchemaGenerator.makeUnExecutableSchema(
                new SchemaParser().parse(Files.readString(SCHEMA, StandardCharsets.UTF_8)));
        List<Operation> operations = load();
        assertThat(operations).extracting(Operation::name).contains("listarPacientes", "obtenerOrdenCobroDetalle",
                "cobrarCuotaEnEfectivo", "generarQrPagoFacil", "simularConfirmacionQrDev", "configurarPlanDePagos",
                "registrarEgresoCaja", "obtenerFlujoCaja", "generarReporteInteligente");
        for (Operation operation : operations) {
            var document = Parser.parse(operation.document());
            assertThat(new Validator().validateDocument(schema, document, Locale.ENGLISH))
                    .as("operation %s", operation.name()).isEmpty();
            var definition = document.getFirstDefinitionOfType(OperationDefinition.class).orElseThrow();
            Map<String, Object> variables = JSON.readValue(operation.variablesJson(), new TypeReference<>() { });
            definition.getVariableDefinitions().forEach(v ->
                    assertThat(variables).as("%s variable %s", operation.name(), v.getName()).containsKey(v.getName()));
        }
    }

    @Test
    void everyIdUsedInVariablesIsAWellFormedUuid() throws IOException {
        for (Operation operation : load()) {
            var matcher = LOCAL_UUID.matcher(operation.variablesJson());
            while (matcher.find()) {
                assertThat(UUID.fromString(matcher.group(1)).toString()).as(operation.name()).isEqualTo(matcher.group(1));
            }
        }
    }

    @Test
    void docEmbedsEveryOperationVerbatim() throws IOException {
        assertThat(DOC).exists();
        String doc = Files.readString(DOC, StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(doc).contains("src/test/resources/local-integration/operations.graphql")
                .contains("Idempotency-Key").contains("X-Dev-User");
        for (Operation operation : load()) {
            assertThat(doc).as("doc must include %s", operation.name()).contains(operation.document())
                    .contains(operation.variablesJson());
        }
    }

    private static List<Operation> load() throws IOException {
        List<Operation> result = new ArrayList<>();
        String name = null;
        String vars = null;
        StringBuilder document = new StringBuilder();
        for (String line : Files.readString(OPERATIONS, StandardCharsets.UTF_8).replace("\r\n", "\n").split("\n")) {
            if (line.startsWith("#@vars ")) {
                vars = line.substring(7).trim();
            } else if (line.startsWith("#@ ")) {
                if (name != null) {
                    result.add(new Operation(name, document.toString().strip(), vars));
                }
                name = line.substring(3).trim();
                vars = null;
                document = new StringBuilder();
            } else if (name != null) {
                document.append(line).append('\n');
            }
        }
        if (name != null) {
            result.add(new Operation(name, document.toString().strip(), vars));
        }
        result.forEach(o -> assertThat(o.variablesJson()).as("#@vars of %s", o.name()).isNotNull());
        return result;
    }
}
