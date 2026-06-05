package dev.dediren.core.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.dediren.contracts.json.JsonSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SchemaValidator {
    private final Path repositoryRoot;
    private final SchemaRegistry schemaRegistry;

    private SchemaValidator(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
        this.schemaRegistry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
    }

    public static SchemaValidator fromRepositoryRoot(Path repositoryRoot) {
        return new SchemaValidator(repositoryRoot.toAbsolutePath().normalize());
    }

    public List<String> compile(String schemaPath) {
        try {
            loadSchema(schemaPath);
            return List.of();
        } catch (IOException | RuntimeException error) {
            return List.of(error.getMessage());
        }
    }

    public List<String> validateFixture(String schemaPath, String fixturePath) {
        try {
            JsonNode document = JsonSupport.objectMapper().readTree(repositoryRoot.resolve(fixturePath).toFile());
            return validate(schemaPath, document);
        } catch (IOException | RuntimeException error) {
            return List.of(error.getMessage());
        }
    }

    public List<String> validate(String schemaPath, JsonNode document) {
        try {
            Schema schema = loadSchema(schemaPath);
            return schema.validate(JsonSupport.objectMapper().writeValueAsString(document), InputFormat.JSON)
                    .stream()
                    .map(Error::getMessage)
                    .sorted()
                    .toList();
        } catch (IOException | RuntimeException error) {
            return List.of(error.getMessage());
        }
    }

    private Schema loadSchema(String schemaPath) throws IOException {
        try (InputStream stream = Files.newInputStream(repositoryRoot.resolve(schemaPath))) {
            return schemaRegistry.getSchema(stream);
        }
    }
}
