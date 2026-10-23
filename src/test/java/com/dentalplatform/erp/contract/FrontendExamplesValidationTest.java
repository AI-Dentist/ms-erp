package com.dentalplatform.erp.contract;

import com.dentalplatform.erp.contract.ContractBlocks.Block;
import com.dentalplatform.erp.support.IntegrationTestBase;
import graphql.GraphQLContext;
import graphql.execution.RawVariables;
import graphql.execution.ValuesResolver;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import graphql.schema.GraphQLSchema;
import graphql.validation.ValidationError;
import graphql.validation.Validator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.GraphQlSource;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates, against the schema the application serves, (a) every valid example of the real contract written in
 * docs/frontend-contract-diff.md and (b) the snapshot of the frontend operations, asserting the validity stated in
 * the doc (VALID / INVALID) and printing the per-operation errors as documentation evidence.
 */
class FrontendExamplesValidationTest extends IntegrationTestBase {

    private static final Path DOC = Path.of("docs/frontend-contract-diff.md");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private GraphQlSource graphQlSource;

    private List<ValidationError> validate(String document) {
        return new Validator().validateDocument(graphQlSource.schema(), Parser.parse(document), Locale.ENGLISH);
    }

    private static String readDoc() throws IOException {
        assertThat(DOC).exists();
        return Files.readString(DOC, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    @Test
    void realExamplesAreValidAgainstTheServedSchemaIncludingVariables() {
        GraphQLSchema schema = graphQlSource.schema();
        List<Block> examples = ContractBlocks.load("real-examples.graphql");
        assertThat(examples).hasSizeGreaterThanOrEqualTo(20);
        for (Block example : examples) {
            assertThat(validate(example.document())).as("example %s", example.name()).isEmpty();
            var operation = Parser.parse(example.document()).getFirstDefinitionOfType(OperationDefinition.class)
                    .orElseThrow();
            assertThat(operation.getVariableDefinitions().isEmpty() || example.variablesJson() != null)
                    .as("example %s declares variables but has no #@vars", example.name()).isTrue();
            Map<String, Object> raw = example.variablesJson() == null ? Map.of()
                    : JSON.readValue(example.variablesJson(), new TypeReference<Map<String, Object>>() { });
            // throws if a variable value cannot be coerced (bad UUID/Date/DateTime/enum, missing non-null field)
            ValuesResolver.coerceVariableValues(schema, operation.getVariableDefinitions(), RawVariables.of(raw),
                    GraphQLContext.getDefault(), Locale.ENGLISH);
        }
    }

    @Test
    void docContainsEveryRealExampleVerbatim() throws IOException {
        String doc = readDoc();
        assertThat(doc).contains("src/test/resources/contract/real-examples.graphql");
        for (Block example : ContractBlocks.load("real-examples.graphql")) {
            assertThat(doc).as("doc must include example %s verbatim", example.name()).contains(example.document());
            if (example.variablesJson() != null) {
                assertThat(doc).as("doc must include variables of %s", example.name())
                        .contains(example.variablesJson());
            }
        }
    }

    @Test
    void frontendOperationsMatchTheValidityStatedInTheDoc() throws IOException {
        List<Block> operations = ContractBlocks.load("frontend-operations.graphql");
        List<String> report = new ArrayList<>();
        int valid = 0;
        for (Block operation : operations) {
            List<ValidationError> errors = validate(operation.document());
            String actual = errors.isEmpty() ? "VALID" : "INVALID";
            valid += errors.isEmpty() ? 1 : 0;
            report.add("%-34s %s%s".formatted(operation.name(), actual,
                    errors.stream().map(e -> "\n      - " + e.getMessage()).reduce("", String::concat)));
            assertThat(actual).as("frontend operation %s", operation.name()).isEqualTo(operation.expectation());
        }
        int invalid = operations.size() - valid;
        System.out.println("=== Frontend operations vs real schema: " + valid + " valid, " + invalid + " invalid of "
                + operations.size() + " ===\n" + String.join("\n", report));
        assertThat(readDoc()).as("doc summary must state the counted operations")
                .contains("**%d operaciones del frontend validadas: %d válidas, %d inválidas**"
                        .formatted(operations.size(), valid, invalid));
    }
}
