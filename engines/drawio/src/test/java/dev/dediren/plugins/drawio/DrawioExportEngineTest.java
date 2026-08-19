package dev.dediren.plugins.drawio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.DiagnosticCode;
import dev.dediren.contracts.DiagnosticSeverity;
import dev.dediren.contracts.export.ExportRequest;
import dev.dediren.contracts.export.ExportResult;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.source.SourceDocument;
import dev.dediren.contracts.source.SourceNode;
import dev.dediren.contracts.source.SourceRelationship;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.plugins.drawio.mx.MxCell;
import dev.dediren.plugins.drawio.mx.MxFile;
import dev.dediren.plugins.drawio.mx.MxReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * End-to-end behaviour of the draw.io exporter: policy handling, the emitted artifact, and the
 * golden `.drawio` for a small ArchiMate model.
 *
 * <p>The golden is checked in at {@code src/test/resources/golden/archimate-basic.drawio}. It is a
 * deliberately small model built in this test rather than read from {@code fixtures/}, so the
 * artifact does not join the four-stage geometry regeneration chain: the coordinates below are
 * literals this test owns, not ELK output that moves when layout changes.
 */
class DrawioExportEngineTest {

  private static final String GOLDEN = "/golden/archimate-basic.drawio";

  private final DrawioExportEngine engine = new DrawioExportEngine();

  private static SourceDocument source() {
    return new SourceDocument(
        "model.schema.v1",
        List.of(),
        List.of(),
        List.of(
            new SourceNode(
                "orders-component", "ApplicationComponent", "Orders Component", Map.of()),
            new SourceNode("orders-service", "ApplicationService", "Orders Service", Map.of())),
        List.of(
            new SourceRelationship(
                "orders-realizes-service",
                "Realization",
                "orders-component",
                "orders-service",
                "realizes",
                Map.of())),
        Map.of(
            "generic-graph",
            JsonSupport.readTree(
                """
                {
                  "semantic_profile": "archimate",
                  "views": [
                    {
                      "id": "main",
                      "label": "Main",
                      "kind": "archimate",
                      "nodes": ["orders-component", "orders-service"],
                      "relationships": ["orders-realizes-service"]
                    }
                  ]
                }
                """)));
  }

  private static LayoutResult layoutResult() {
    return new LayoutResult(
        "layout-result.schema.v1",
        "main",
        List.of(
            new LaidOutNode(
                "orders-component", "orders-component", null, 12, 12, 160, 80, "Orders Component"),
            new LaidOutNode(
                "orders-service", "orders-service", null, 254, 12, 160, 80, "Orders Service")),
        List.of(
            new LaidOutEdge(
                "orders-realizes-service",
                "orders-component",
                "orders-service",
                "orders-realizes-service",
                null,
                List.of(),
                List.of(new Point(173, 52), new Point(253, 52)),
                "realizes")),
        List.of(),
        List.of());
  }

  private static JsonNode policy(String json) {
    return JsonSupport.readTree(json);
  }

  private static JsonNode validPolicy() {
    return policy(
        """
        {
          "drawio_export_policy_schema_version": "drawio-export-policy.schema.v1",
          "diagram_name": "Main"
        }
        """);
  }

  private static ExportRequest request(JsonNode policy) {
    return new ExportRequest(
        ContractVersions.EXPORT_REQUEST_SCHEMA_VERSION, source(), layoutResult(), policy);
  }

  private static String golden() throws IOException {
    try (InputStream stream = DrawioExportEngineTest.class.getResourceAsStream(GOLDEN)) {
      assertThat(stream).as("golden resource %s", GOLDEN).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  // ---------------------------------------------------------------- the artifact

  @Test
  void publishesTheDrawioArtifactKindAndTheExportResultSchemaVersion() throws Exception {
    EngineResult<ExportResult> result = engine.export(request(validPolicy()), Map.of(), null);

    assertThat(result.value().artifactKind()).isEqualTo(DrawioExportEngine.ARTIFACT_KIND);
    assertThat(result.value().exportResultSchemaVersion())
        .isEqualTo(ContractVersions.EXPORT_RESULT_SCHEMA_VERSION);
  }

  @Test
  void emitsAnArtifactTheMxfileReaderAccepts() throws Exception {
    // The cheapest available proxy for "draw.io can open this", and the seam the round-trip test
    // builds on. Nothing in this build can launch the editor.
    MxFile reread =
        MxReader.read(engine.export(request(validPolicy()), Map.of(), null).value().content());

    assertThat(reread.diagrams()).hasSize(1);
    assertThat(reread.diagrams().get(0).name()).isEqualTo("Main");
    assertThat(reread.diagrams().get(0).compressed()).isFalse();
    assertThat(reread.diagrams().get(0).cells())
        .extracting(MxCell::id)
        .startsWith("0", "1")
        .hasSize(6);
  }

  @Test
  void matchesTheCheckedInGoldenByteForByte() throws Exception {
    String content = engine.export(request(validPolicy()), Map.of(), null).value().content();

    assertThat(content)
        .as(
            "the golden is the reviewable record of what a `.drawio` export looks like;"
                + " read the diff rather than re-baselining it")
        .isEqualTo(golden());
  }

  @Test
  void namesThePageAfterThePerViewOverrideWhenThePolicyCarriesOne() throws Exception {
    JsonNode withOverride =
        policy(
            """
            {
              "drawio_export_policy_schema_version": "drawio-export-policy.schema.v1",
              "diagram_name": "Fallback",
              "views": { "main": { "diagram_name": "Orders" } }
            }
            """);

    MxFile reread =
        MxReader.read(engine.export(request(withOverride), Map.of(), null).value().content());

    assertThat(reread.diagrams().get(0).name()).isEqualTo("Orders");
  }

  @Test
  void reportsNoWarningForAModelEveryShapeCovers() throws Exception {
    EngineResult<ExportResult> result = engine.export(request(validPolicy()), Map.of(), null);

    assertThat(result.diagnostics())
        .extracting(Diagnostic::severity)
        .doesNotContain(DiagnosticSeverity.WARNING, DiagnosticSeverity.ERROR);
  }

  // ---------------------------------------------------------------- policy handling

  @Test
  void refusesAPolicyThatIsNotAnObject() {
    assertThatPolicyIsRejected(JsonSupport.readTree("[]"));
  }

  @Test
  void refusesAPolicyMissingARequiredField() {
    assertThatPolicyIsRejected(
        policy(
            """
            { "drawio_export_policy_schema_version": "drawio-export-policy.schema.v1" }
            """));
  }

  @Test
  void refusesAPolicyDeclaringAnotherSchemaVersion() {
    assertThatPolicyIsRejected(
        policy(
            """
            {
              "drawio_export_policy_schema_version": "drawio-export-policy.schema.v99",
              "diagram_name": "Main"
            }
            """));
  }

  @Test
  void refusesAPolicyCarryingAFieldTheSchemaDoesNotDeclare() {
    // The schema is additionalProperties:false, so a typo'd key is a mistake worth reporting
    // rather than a setting silently ignored.
    assertThatPolicyIsRejected(
        policy(
            """
            {
              "drawio_export_policy_schema_version": "drawio-export-policy.schema.v1",
              "diagram_name": "Main",
              "diagramName": "Main"
            }
            """));
  }

  @Test
  void refusesABlankDiagramName() {
    assertThatPolicyIsRejected(
        policy(
            """
            {
              "drawio_export_policy_schema_version": "drawio-export-policy.schema.v1",
              "diagram_name": "   "
            }
            """));
  }

  private void assertThatPolicyIsRejected(JsonNode invalid) {
    assertThatThrownBy(() -> engine.export(request(invalid), Map.of(), null))
        .isInstanceOfSatisfying(
            EngineException.class,
            failure -> {
              assertThat(failure.exitCode()).isEqualTo(3);
              assertThat(failure.diagnostics())
                  .singleElement()
                  .satisfies(
                      diagnostic -> {
                        assertThat(diagnostic.code())
                            .isEqualTo(DiagnosticCode.DRAWIO_POLICY_INVALID.code());
                        assertThat(diagnostic.severity()).isEqualTo(DiagnosticSeverity.ERROR);
                        assertThat(diagnostic.path()).isEqualTo("policy");
                      });
            });
  }
}
