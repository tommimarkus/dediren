package dev.dediren.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.testsupport.SchemaAssertions;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

class DiagnosticProvenanceTest {
  @Test
  void diagnosticWithSourcePointerSerializesSnakeCaseAndRoundTrips() throws Exception {
    Diagnostic diagnostic =
        new Diagnostic(
            "DEDIREN_TEST", DiagnosticSeverity.WARNING, "careful", "/nodes/0", "/nodes/0");

    JsonNode encoded = JsonSupport.objectMapper().valueToTree(diagnostic);

    assertThat(encoded.at("/source_pointer").asText()).isEqualTo("/nodes/0");

    Diagnostic reparsed = JsonSupport.objectMapper().treeToValue(encoded, Diagnostic.class);

    assertThat(reparsed).isEqualTo(diagnostic);
    assertThat(reparsed.sourcePointer()).isEqualTo("/nodes/0");
  }

  @Test
  void fourArgConstructorStillCompilesAndLeavesSourcePointerNull() throws Exception {
    Diagnostic diagnostic =
        new Diagnostic("DEDIREN_TEST", DiagnosticSeverity.ERROR, "failed", "/nodes/1");

    assertThat(diagnostic.sourcePointer()).isNull();
    assertThat(diagnostic.path()).isEqualTo("/nodes/1");

    JsonNode encoded = JsonSupport.objectMapper().valueToTree(diagnostic);

    assertThat(encoded.has("source_pointer")).isFalse();

    Diagnostic reparsed = JsonSupport.objectMapper().treeToValue(encoded, Diagnostic.class);

    assertThat(reparsed).isEqualTo(diagnostic);
  }

  @Test
  void diagnosticWithSourcePointerValidatesAgainstEveryPublishingSchema() {
    JsonNode diagnostic =
        JsonSupport.readTree(
            """
            {
              "code": "DEDIREN_TEST",
              "severity": "warning",
              "message": "careful",
              "path": "/nodes/0",
              "source_pointer": "/nodes/0"
            }
            """);

    for (String schemaPath :
        java.util.List.of(
            "schemas/envelope.schema.json",
            "schemas/layout-result.schema.json",
            "schemas/build-result.schema.json")) {
      assertThat(
              SchemaAssertions.validate(workspaceRoot(), schemaPath, wrap(schemaPath, diagnostic)))
          .describedAs(schemaPath)
          .isEmpty();
    }
  }

  private static JsonNode wrap(String schemaPath, JsonNode diagnostic) {
    var mapper = JsonSupport.objectMapper();
    var root = mapper.createObjectNode();
    if (schemaPath.endsWith("envelope.schema.json")) {
      root.put("envelope_schema_version", "envelope.schema.v1");
      root.put("status", "warning");
      root.putArray("diagnostics").add(diagnostic);
    } else if (schemaPath.endsWith("layout-result.schema.json")) {
      root.put("layout_result_schema_version", "layout-result.schema.v2");
      root.put("view_id", "main");
      root.putArray("nodes");
      root.putArray("edges");
      root.putArray("groups");
      root.putArray("warnings").add(diagnostic);
    } else {
      root.put("build_result_schema_version", "build-result.schema.v1");
      root.put("status", "warning");
      root.putArray("views");
      root.putArray("diagnostics").add(diagnostic);
    }
    return root;
  }

  private static Path workspaceRoot() {
    return dev.dediren.testsupport.TestSupport.workspaceRoot();
  }
}
