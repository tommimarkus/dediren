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
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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

  static Stream<Arguments> everyFamily() {
    // The eight shipped families plus the two extra view-scopings MainTest pins as goldens: every
    // family's per-view export must be well-formed XMI the REAL pinned OMG XMI.xsd accepts, not
    // just
    // the class family the single-fixture case once covered. (source fixture, layout-result
    // fixture.)
    return Stream.of(
        Arguments.of("class", "valid-uml-basic", "uml-basic"),
        Arguments.of("complex-class", "valid-uml-complex", "uml-complex-class"),
        Arguments.of("sequence", "valid-uml-sequence-basic", "uml-sequence-basic"),
        Arguments.of(
            "sequence-fragments", "valid-uml-sequence-fragments", "uml-sequence-fragments"),
        Arguments.of("state-machine", "valid-uml-state-machine-basic", "uml-state-machine-basic"),
        Arguments.of("use-case", "valid-uml-use-case-basic", "uml-use-case-basic"),
        Arguments.of("component", "valid-uml-component-basic", "uml-component-basic"),
        Arguments.of("deployment", "valid-uml-deployment-basic", "uml-deployment-basic"),
        Arguments.of("activity", "valid-uml-basic", "uml-activity"),
        Arguments.of("data", "valid-uml-basic", "uml-data"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("everyFamily")
  void everyFamilyExportValidatesAgainstThePinnedRealOmgXmiSchema(
      String label, String source, String layout) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.put("export_request_schema_version", "export-request.schema.v1");
    input.set("source", fixtureJson("fixtures/source/" + source + ".json"));
    input.set("layout_result", fixtureJson("fixtures/layout-result/" + layout + ".json"));
    input.set("policy", fixtureJson("fixtures/export-policy/default-uml-xmi.json"));
    ExportRequest request =
        engine.parseRequest(
            JsonSupport.objectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8));

    EngineResult<ExportResult> result =
        engine.export(request, realSchemaCacheEnv(), Path.of("").toAbsolutePath());

    assertThat(result.value().content()).describedAs("%s export", label).contains("<uml:Model");
    assertThat(result.diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_EXPORT_SCHEMA_CONFORMANCE");
              assertThat(diagnostic.message())
                  .contains("pinned OMG XMI 2.5.1")
                  .contains("UML-namespace content accepted");
            });
  }

  @Test
  void wholeModelUmldiExportValidatesAgainstThePinnedRealOmgXmiSchema() throws Exception {
    // The whole-model lane emits OMG UMLDI (umldi:/di:/dc:) alongside <uml:Model>. Against the REAL
    // pinned XMI.xsd, those DI namespaces have no declaration either — this pins that they ride the
    // same tolerated no-normative-XSD gap as the UML content and do not flip the export to invalid.
    dev.dediren.engine.ModelExportRequest request =
        new dev.dediren.engine.ModelExportRequest(
            JsonSupport.objectMapper()
                .treeToValue(
                    fixtureJson("fixtures/source/valid-uml-basic.json"),
                    dev.dediren.contracts.source.SourceDocument.class),
            java.util.List.of(
                new dev.dediren.engine.ModelExportRequest.ViewLayout(
                    "class-view",
                    JsonSupport.objectMapper()
                        .treeToValue(
                            fixtureJson("fixtures/layout-result/uml-basic.json"),
                            dev.dediren.contracts.layout.LayoutResult.class))),
            fixtureJson("fixtures/export-policy/default-uml-xmi.json"));

    java.util.Optional<EngineResult<ExportResult>> result =
        engine.exportModel(request, realSchemaCacheEnv(), Path.of("").toAbsolutePath());

    assertThat(result.orElseThrow().value().content())
        .contains("<uml:Model")
        .contains("<umldi:UMLDiagram")
        .contains("<dc:Bounds");
    assertThat(result.orElseThrow().diagnostics())
        .anySatisfy(
            diagnostic -> {
              assertThat(diagnostic.code()).isEqualTo("DEDIREN_EXPORT_SCHEMA_CONFORMANCE");
              assertThat(diagnostic.message())
                  .contains("pinned OMG XMI 2.5.1")
                  .contains("UML-namespace content accepted");
            });
  }

  private static Map<String, String> realSchemaCacheEnv() {
    return Map.of(
        "DEDIREN_SCHEMA_CACHE_DIR",
        System.getProperty(
            "dediren.real-schemas.cache",
            Path.of(System.getProperty("user.home"), ".cache", "dediren-real-schemas").toString()));
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
