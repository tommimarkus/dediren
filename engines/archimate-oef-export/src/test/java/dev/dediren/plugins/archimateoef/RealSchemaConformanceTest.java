package dev.dediren.plugins.archimateoef;

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
 * pinned Open Group ArchiMate 3.1 XSD set via the engine's own SHA-256-verified cache download —
 * not the permissive stubs the default suites use. Run with {@code -Ddediren.real-schemas=true};
 * the first run needs network (or a warm cache directory), later runs are offline. The cache
 * location defaults to {@code ~/.cache/dediren-real-schemas} and can be overridden with {@code
 * -Ddediren.real-schemas.cache=<dir>}.
 */
@EnabledIfSystemProperty(named = "dediren.real-schemas", matches = "true")
class RealSchemaConformanceTest {

  private final OefExportEngine engine = new OefExportEngine();

  @Test
  void fixtureExportValidatesAgainstThePinnedRealOpenGroupSchemaSet() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/valid-archimate-oef.json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/archimate-oef-basic.json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-oef.json"));
    ExportRequest request =
        engine.parseRequest(
            JsonSupport.objectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8));

    EngineResult<ExportResult> result =
        engine.export(request, realSchemaCacheEnv(), Path.of("").toAbsolutePath());

    assertThat(result.value().content()).contains("<model");
    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_EXPORT_SCHEMA_CONFORMANCE");
              assertThat(diagnostic.message()).contains("pinned Open Group");
            });
  }

  static Map<String, String> realSchemaCacheEnv() {
    String cache =
        System.getProperty(
            "dediren.real-schemas.cache",
            Path.of(System.getProperty("user.home"), ".cache", "dediren-real-schemas").toString());
    return Map.of("DEDIREN_SCHEMA_CACHE_DIR", cache);
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
