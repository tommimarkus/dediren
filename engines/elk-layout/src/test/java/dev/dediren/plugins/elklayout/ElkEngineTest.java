package dev.dediren.plugins.elklayout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LaidOutSceneMapper;
import dev.dediren.ir.LayoutRequestMapper;
import dev.dediren.ir.SceneGraph;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Pins the {@link ElkEngine} seam's envelope serialization: {@code
 * layoutEnvelopeRoundTripsThroughHarness} wraps the engine's result in a command envelope through
 * the test-only {@link Main} harness and unwraps its {@code data}, asserting it JSON-equals the
 * value the engine returned directly. Post-cutover that harness delegates to this same engine, so
 * the guarantee is envelope wrap/unwrap round-trip stability, not the cross-process parity the
 * retired plugin process boundary once provided. Unlike the other engines, elk-layout publishes a
 * dedicated parse-failure envelope ({@code DEDIREN_ELK_INPUT_INVALID_JSON} / exit 3), so the parse
 * entry point maps failures to a structured {@link EngineException} instead of surfacing a raw
 * exit.
 */
class ElkEngineTest {
  private static final String VALID_REQUEST =
      """
          {
            "layout_request_schema_version": "%s",
            "view_id": "main",
            "nodes": [
              {"id": "client", "label": "Client", "source_id": "client", "width_hint": 160, "height_hint": 80},
              {"id": "api", "label": "API", "source_id": "api", "width_hint": 160, "height_hint": 80}
            ],
            "edges": [
              {"id": "client-calls-api", "source": "client", "target": "api", "label": "calls", "source_id": "client-calls-api"}
            ],
            "groups": [],
            "constraints": []
          }
          """
          .formatted(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);

  private final ElkEngine engine = new ElkEngine();

  @Test
  void idIsElkLayout() {
    assertEquals("elk-layout", engine.id());
  }

  @Test
  void layoutEnvelopeRoundTripsThroughHarness() throws Exception {
    SceneGraph scene = engine.parseRequest(VALID_REQUEST.getBytes(StandardCharsets.UTF_8));

    EngineResult<LaidOutScene> result = engine.layout(scene);

    assertEquals(
        processData(VALID_REQUEST), engineTree(LaidOutSceneMapper.toResult(result.value())));
  }

  @Test
  void parseRequestRejectsUnparseableInputWithInputEnvelopeCode() {
    EngineException failure =
        assertThrows(
            EngineException.class,
            () -> engine.parseRequest("not-json".getBytes(StandardCharsets.UTF_8)));

    assertEquals(3, failure.exitCode());
    assertEquals("DEDIREN_ELK_INPUT_INVALID_JSON", failure.diagnostics().get(0).code());
  }

  @Test
  void parseRequestRejectsUnsupportedPreferenceWithLayoutFailedCode() {
    String request =
        """
            {
              "layout_request_schema_version": "%s",
              "view_id": "main",
              "nodes": [],
              "edges": [],
              "groups": [],
              "constraints": [],
              "layout_preferences": { "direction": "diagonal" }
            }
            """
            .formatted(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);

    EngineException failure =
        assertThrows(
            EngineException.class,
            () -> engine.parseRequest(request.getBytes(StandardCharsets.UTF_8)));

    assertEquals(3, failure.exitCode());
    assertEquals("DEDIREN_ELK_LAYOUT_FAILED", failure.diagnostics().get(0).code());
  }

  @Test
  void layoutFailureThrowsLayoutFailedCode() throws Exception {
    String request =
        """
            {
              "layout_request_schema_version": "%s",
              "view_id": "main",
              "nodes": [
                {"id": "client", "source_id": "client", "width_hint": 160, "height_hint": 80}
              ],
              "edges": [],
              "groups": [],
              "constraints": []
            }
            """
            .formatted(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
    SceneGraph parsed = engine.parseRequest(request.getBytes(StandardCharsets.UTF_8));

    EngineException failure = assertThrows(EngineException.class, () -> engine.layout(parsed));

    assertEquals(3, failure.exitCode());
    assertEquals("DEDIREN_ELK_LAYOUT_FAILED", failure.diagnostics().get(0).code());
  }

  @Test
  void parseRequestThenLayoutMatchesDirectRecordLayout() throws Exception {
    // Both `laid` and `viaRecord` derive from the identical toRequest(toSceneGraph(parsedRequest)),
    // so any pre-layout id/sourceId conflation in the boundary adapters would cancel out on both
    // sides (that guard lives in LayoutRequestMapperTest, not here). What this test actually pins
    // is toResult(toScene(...)) == identity on a real ELK-produced LayoutResult carrying populated
    // geometry/routes, not just the pre-layout request shape.
    String request =
        """
            {
              "layout_request_schema_version": "%s",
              "view_id": "main",
              "nodes": [
                {"id": "client-1", "label": "Client", "source_id": "client", "width_hint": 160, "height_hint": 80},
                {"id": "api-1", "label": "API", "source_id": "api", "width_hint": 160, "height_hint": 80}
              ],
              "edges": [
                {"id": "client-calls-api", "source": "client-1", "target": "api-1", "label": "calls", "source_id": "client-calls-api"}
              ],
              "groups": [],
              "constraints": []
            }
            """
            .formatted(ContractVersions.LAYOUT_REQUEST_SCHEMA_VERSION);
    byte[] bytes = request.getBytes(StandardCharsets.UTF_8);
    ElkEngine engine = new ElkEngine();

    SceneGraph scene = engine.parseRequest(bytes);
    LaidOutScene laid = engine.layout(scene).value();
    LayoutResult viaRecord = new ElkLayoutEngine().layout(LayoutRequestMapper.toRequest(scene));

    assertThat(LaidOutSceneMapper.toResult(laid)).isEqualTo(viaRecord);
  }

  private static JsonNode engineTree(Object value) {
    return JsonSupport.objectMapper()
        .readTree(JsonSupport.objectMapper().writeValueAsString(value));
  }

  private static JsonNode processData(String request) throws Exception {
    PluginResult result = Main.executeForTesting(new String[] {"layout"}, request);
    assertEquals(0, result.exitCode(), result.stdout());
    return JsonSupport.objectMapper().readTree(result.stdout()).get("data");
  }
}
