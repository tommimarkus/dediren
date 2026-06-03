package dev.dediren.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SchemaAssertions {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    private SchemaAssertions() {
    }

    public static List<String> compile(Path repositoryRoot, String schemaPath) {
        try {
            loadSchema(repositoryRoot, schemaPath);
            return List.of();
        } catch (IOException | RuntimeException error) {
            return List.of(error.getMessage());
        }
    }

    public static List<String> validateFixture(Path repositoryRoot, String schemaPath, String fixturePath) {
        try {
            JsonNode document = OBJECT_MAPPER.readTree(repositoryRoot.resolve(fixturePath).toFile());
            return validate(repositoryRoot, schemaPath, document);
        } catch (IOException | RuntimeException error) {
            return List.of(error.getMessage());
        }
    }

    public static List<String> validate(Path repositoryRoot, String schemaPath, JsonNode data) {
        try {
            Schema schema = loadSchema(repositoryRoot, schemaPath);
            return schema.validate(OBJECT_MAPPER.writeValueAsString(data), InputFormat.JSON)
                    .stream()
                    .map(Error::getMessage)
                    .sorted()
                    .toList();
        } catch (IOException | RuntimeException error) {
            return List.of(error.getMessage());
        }
    }

    public static void assertSchemaValid(Path repositoryRoot, String schemaPath, JsonNode data) {
        assertThat(validate(repositoryRoot, schemaPath, data))
                .describedAs(schemaPath)
                .isEmpty();
    }

    private static Schema loadSchema(Path repositoryRoot, String schemaPath) throws IOException {
        try (InputStream stream = Files.newInputStream(repositoryRoot.resolve(schemaPath))) {
            return SCHEMA_REGISTRY.getSchema(stream);
        }
    }
}
