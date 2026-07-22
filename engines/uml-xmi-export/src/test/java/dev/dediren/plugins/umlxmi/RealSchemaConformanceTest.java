package dev.dediren.plugins.umlxmi;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Opt-in real-standards lane (architecture-guidelines §12): validates the emitter against the REAL,
 * pinned OMG XMI 2.5.1 {@code XMI.xsd} via the engine's own SHA-256-verified cache download — not
 * the permissive stub the default suites use. The known, tolerated finding is the
 * no-normative-UML-XSD gap, which the conformance diagnostic must disclose. Run with {@code
 * -Ddediren.real-schemas=true}; the first run needs network (or a warm cache directory). The cache
 * location defaults to {@code ~/.cache/dediren-real-schemas}, overridable with {@code
 * -Ddediren.real-schemas.cache=<dir>}.
 */
@EnabledIfSystemProperty(named = "dediren.real-schemas", matches = "true")
class RealSchemaConformanceTest {

  private final XmiExportEngine engine = new XmiExportEngine();

  @Test
  void fixtureExportValidatesAgainstThePinnedRealOmgXmiSchema() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/valid-uml-basic.json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/uml-basic.json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));
    ExportRequest request =
        engine.parseRequest(
            JsonSupport.objectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8));

    EngineResult<ExportResult> result =
        engine.export(
            request,
            Map.of(
                "DEDIREN_SCHEMA_CACHE_DIR",
                System.getProperty(
                    "dediren.real-schemas.cache",
                    Path.of(System.getProperty("user.home"), ".cache", "dediren-real-schemas")
                        .toString())),
            Path.of("").toAbsolutePath());

    assertThat(result.value().content()).contains("<uml:Model");
    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_EXPORT_SCHEMA_CONFORMANCE");
              assertThat(diagnostic.message())
                  .contains("pinned OMG XMI 2.5.1")
                  .contains("UML-namespace content accepted");
            });
  }

  private static JsonNode fixtureJson(String path) throws Exception {
    return JsonSupport.objectMapper().readTree(Files.readString(workspaceRoot().resolve(path)));
  }

  private static Path workspaceRoot() {
    Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (current != null) {
      if (Files.exists(current.resolve("schemas/model.schema.json"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("Could not locate repository root from user.dir");
  }
}
