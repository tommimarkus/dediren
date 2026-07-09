package dev.dediren.plugins.elklayout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.ContractVersions;
import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.layout.LayoutRequest;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
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
    LayoutRequest request = engine.parseRequest(VALID_REQUEST.getBytes(StandardCharsets.UTF_8));

    EngineResult<LayoutResult> result = engine.layout(request);

    assertEquals(processData(VALID_REQUEST), engineTree(result.value()));
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
    LayoutRequest parsed = engine.parseRequest(request.getBytes(StandardCharsets.UTF_8));

    EngineException failure = assertThrows(EngineException.class, () -> engine.layout(parsed));

    assertEquals(3, failure.exitCode());
    assertEquals("DEDIREN_ELK_LAYOUT_FAILED", failure.diagnostics().get(0).code());
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
