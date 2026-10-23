package com.dentalplatform.erp.contract;

import com.dentalplatform.erp.support.IntegrationTestBase;
import graphql.GraphQL;
import graphql.introspection.IntrospectionQuery;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaPrinter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.graphql.execution.GraphQlSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeps docs/graphql/schema.graphqls and docs/graphql/introspection.json equal to the schema the application
 * really serves. Regenerate with {@code -Dcontract.regenerate=true}; the default mode only compares.
 */
class SchemaExportTest extends IntegrationTestBase {

    private static final Path SDL = Path.of("docs/graphql/schema.graphqls");
    private static final Path INTROSPECTION = Path.of("docs/graphql/introspection.json");
    private static final boolean REGENERATE = Boolean.getBoolean("contract.regenerate");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private GraphQlSource graphQlSource;

    private String printedSchema(GraphQLSchema schema) {
        var options = SchemaPrinter.Options.defaultOptions()
                .includeDirectives(false)
                .includeSchemaDefinition(true)
                .includeScalarTypes(true);
        return new SchemaPrinter(options).print(schema).replace("\r\n", "\n").strip() + "\n";
    }

    private JsonNode introspection(GraphQLSchema schema) {
        var result = GraphQL.newGraphQL(schema).build().execute(IntrospectionQuery.INTROSPECTION_QUERY);
        assertThat(result.getErrors()).isEmpty();
        return JSON.valueToTree(result.toSpecification());
    }

    private static String read(Path path) throws IOException {
        assertThat(path).as("%s missing; run with -Dcontract.regenerate=true", path).exists();
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    @Test
    void sdlDocEqualsTheServedSchema() throws IOException {
        String served = printedSchema(graphQlSource.schema());
        if (REGENERATE) {
            write(SDL, served);
        }
        assertThat(read(SDL)).as("docs/graphql/schema.graphqls drifted from the served schema").isEqualTo(served);
    }

    @Test
    void introspectionDocEqualsTheServedSchema() throws IOException {
        JsonNode served = introspection(graphQlSource.schema());
        if (REGENERATE) {
            write(INTROSPECTION, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(served) + "\n");
        }
        assertThat(JSON.readTree(read(INTROSPECTION)))
                .as("docs/graphql/introspection.json drifted from the served schema")
                .isEqualTo(served);
    }
}
