package dev.dediren.contracts.schema;

import com.fasterxml.jackson.databind.JsonNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import dev.dediren.contracts.json.JsonSupport;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class SchemaValidator {
    private final Path repositoryRoot;
    private final JsonSchemaFactory schemaFactory;

    private SchemaValidator(Path repositoryRoot) {
        this.repositoryRoot = repositoryRoot;
        this.schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
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
            JsonSchema schema = loadSchema(schemaPath);
            JsonNode document = JsonSupport.objectMapper().readTree(repositoryRoot.resolve(fixturePath).toFile());
            return schema.validate(document)
                    .stream()
                    .map(ValidationMessage::getMessage)
                    .sorted()
                    .toList();
        } catch (IOException | RuntimeException error) {
            return List.of(error.getMessage());
        }
    }

    private JsonSchema loadSchema(String schemaPath) throws IOException {
        try (InputStream stream = Files.newInputStream(repositoryRoot.resolve(schemaPath))) {
            return schemaFactory.getSchema(stream);
        }
    }
}
