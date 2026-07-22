package dev.dediren.core.schema;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import dev.dediren.contracts.json.JsonSupport;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import tools.jackson.databind.JsonNode;

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

  public List<String> validate(String schemaPath, JsonNode document) {
    Schema schema;
    try {
      schema = loadSchema(schemaPath);
    } catch (IOException | RuntimeException error) {
      // A schema that cannot be loaded is a broken product install, not an invalid document: it
      // must ride the command I/O lane (DEDIREN_COMMAND_IO_FAILED), never be reported against the
      // user's model as DEDIREN_SCHEMA_INVALID.
      throw new UncheckedIOException(
          "product schema " + schemaPath + " could not be loaded: " + error.getMessage(),
          error instanceof IOException io ? io : new IOException(error));
    }
    try {
      return schema
          .validate(JsonSupport.objectMapper().writeValueAsString(document), InputFormat.JSON)
          .stream()
          .map(Error::getMessage)
          .sorted()
          .toList();
    } catch (RuntimeException error) {
      return List.of(String.valueOf(error.getMessage()));
    }
  }

  private Schema loadSchema(String schemaPath) throws IOException {
    try (InputStream stream = Files.newInputStream(repositoryRoot.resolve(schemaPath))) {
      return schemaRegistry.getSchema(stream);
    }
  }
}
