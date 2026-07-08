package dev.dediren.core.commands;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.Diagnostic;
import dev.dediren.contracts.EnvelopeStatus;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.core.plugins.PluginRunOutcome;
import dev.dediren.engine.EngineResult;
import dev.dediren.engine.Engines;
import dev.dediren.engine.LayoutEngine;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreCommandsTest {

  @Test
  void semanticValidateCommandRejectsInvalidSourceBeforeEngineLookup() throws Exception {
    // Source validation runs before engine resolution, so an invalid source is reported as its own
    // diagnostics even when the requested engine id does not exist in the registry.
    String input =
        """
                {
                  "model_schema_version": "model.schema.v1",
                  "nodes": [
                    { "id": "api", "type": "ApplicationComponent", "label": "API", "properties": {} }
                  ],
                  "relationships": [
                    {
                      "id": "missing-source",
                      "type": "Association",
                      "source": "missing",
                      "target": "api",
                      "label": "invalid",
                      "properties": {}
                    }
                  ],
                  "plugins": { "generic-graph": { "views": [] } }
                }
                """;

    PluginRunOutcome outcome =
        CoreCommands.semanticValidateCommand(
            "missing-engine", "archimate", input, null, Map.of(), emptyEngines());

    assertThat(outcome.exitCode()).isEqualTo(2);
    assertThat(outcome.stdout()).contains("DEDIREN_DANGLING_ENDPOINT");
  }

  @Test
  void validateLayoutCommandSurfacesQualityWarningAtEnvelopeLevel() throws Exception {
    String layout =
        """
                {
                  "layout_result_schema_version": "layout-result.schema.v1",
                  "view_id": "main",
                  "nodes": [
                    { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" },
                    { "id": "b", "source_id": "b", "projection_id": "b", "x": 50.0, "y": 20.0, "width": 100.0, "height": 80.0, "label": "B" }
                  ],
                  "edges": [],
                  "groups": [],
                  "warnings": []
                }
                """;

    var result = CoreCommands.validateLayoutCommand(layout);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().status()).isEqualTo(EnvelopeStatus.WARNING);
    assertThat(result.envelope().diagnostics())
        .extracting(Diagnostic::code)
        .containsOnly("DEDIREN_LAYOUT_QUALITY_WARNING");
    assertThat(result.envelope().data().at("/status").asText()).isEqualTo("warning");
    assertThat(result.envelope().data().at("/overlap_count").asInt()).isEqualTo(1);
  }

  @Test
  void validateLayoutCommandKeepsOkEnvelopeForCleanLayout() throws Exception {
    String layout =
        """
                {
                  "layout_result_schema_version": "layout-result.schema.v1",
                  "view_id": "main",
                  "nodes": [
                    { "id": "a", "source_id": "a", "projection_id": "a", "x": 0.0, "y": 0.0, "width": 100.0, "height": 80.0, "label": "A" }
                  ],
                  "edges": [],
                  "groups": [],
                  "warnings": []
                }
                """;

    var result = CoreCommands.validateLayoutCommand(layout);

    assertThat(result.exitCode()).isZero();
    assertThat(result.envelope().status()).isEqualTo(EnvelopeStatus.OK);
    assertThat(result.envelope().diagnostics()).isEmpty();
  }

  @Test
  void layoutCommandRunsThroughTheBoundEngine() throws Exception {
    Engines engines =
        Engines.of(List.of(), List.of(new FakeLayoutEngine("fake-layout")), List.of(), List.of());
    String request =
        """
                {
                  "layout_request_schema_version": "layout-request.schema.v1",
                  "view_id": "main",
                  "nodes": [],
                  "edges": [],
                  "groups": [],
                  "constraints": []
                }
                """;

    PluginRunOutcome outcome =
        CoreCommands.layoutCommand("fake-layout", request, Map.of(), engines);

    assertThat(outcome.exitCode()).isZero();
    assertThat(outcome.stdout()).contains("\"layout_result_schema_version\"");
  }

  @Test
  void layoutCommandUnwrapsAPipedStageEnvelopeBeforeTheEngineParses() throws Exception {
    // Chained-workflow convenience: the previous stage's command envelope is accepted directly;
    // its .data payload is what reaches the engine's parse entry point.
    FakeLayoutEngine engine = new FakeLayoutEngine("fake-layout");
    Engines engines = Engines.of(List.of(), List.of(engine), List.of(), List.of());
    String enveloped =
        """
                {
                  "envelope_schema_version": "envelope.schema.v1",
                  "status": "ok",
                  "data": {
                    "layout_request_schema_version": "layout-request.schema.v1",
                    "view_id": "piped-view",
                    "nodes": [],
                    "edges": [],
                    "groups": [],
                    "constraints": []
                  },
                  "diagnostics": []
                }
                """;

    PluginRunOutcome outcome =
        CoreCommands.layoutCommand("fake-layout", enveloped, Map.of(), engines);

    assertThat(outcome.exitCode()).isZero();
    assertThat(engine.lastParsedViewId).isEqualTo("piped-view");
  }

  private static Engines emptyEngines() {
    return Engines.of(List.of(), List.of(), List.of(), List.of());
  }

  private static final class FakeLayoutEngine implements LayoutEngine {
    private final String id;
    private String lastParsedViewId;

    FakeLayoutEngine(String id) {
      this.id = id;
    }

    @Override
    public String id() {
      return id;
    }

    @Override
    public LayoutRequest parseRequest(byte[] input) {
      LayoutRequest request = JsonSupport.objectMapper().readValue(input, LayoutRequest.class);
      lastParsedViewId = request.viewId();
      return request;
    }

    @Override
    public EngineResult<LayoutResult> layout(LayoutRequest request) {
      return new EngineResult<>(
          new LayoutResult(
              ContractVersions.LAYOUT_RESULT_SCHEMA_VERSION,
              request.viewId(),
              List.of(),
              List.of(),
              List.of(),
              List.of()),
          List.of());
    }
  }
}
