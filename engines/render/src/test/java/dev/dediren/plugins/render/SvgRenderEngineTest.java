package dev.dediren.plugins.render;

import static dev.dediren.plugins.render.svg.SvgDocument.renderSvg;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.contracts.render.RenderPolicy;
import dev.dediren.contracts.render.RenderResult;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import dev.dediren.ir.LaidOutScene;
import dev.dediren.ir.LaidOutSceneMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Pins the {@link SvgRenderEngine} seam's envelope serialization: {@code
 * renderEnvelopeRoundTripsThroughHarness} wraps the engine's result in a command envelope through
 * the test-only {@link Main} harness and unwraps its {@code data}, asserting it JSON-equals the
 * value the engine returned directly. Post-cutover that harness delegates to this same engine, so
 * the guarantee is envelope wrap/unwrap round-trip stability, not the cross-process parity the
 * retired plugin process boundary once provided. The remaining cases pin that published render
 * diagnostics throw {@link EngineException} with the same code and exit code, and that unparseable
 * input surfaces as a raw (non-enveloped) parse failure through the engine's parse entry point.
 */
class SvgRenderEngineTest {
  private final SvgRenderEngine engine = new SvgRenderEngine();

  @Test
  void idIsRender() {
    assertThat(engine.id()).isEqualTo("render");
  }

  @Test
  void renderEnvelopeRoundTripsThroughHarness() throws Exception {
    byte[] input =
        renderInput("fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json");

    SvgRenderEngine.ParsedInput parsed = engine.parseInput(input);
    EngineResult<?> result =
        engine.render(
            LaidOutSceneMapper.toScene(parsed.layoutResult()),
            parsed.policy(),
            parsed.renderMetadata());

    assertThat(engineTree(result.value())).isEqualTo(processData(input));
  }

  @Test
  void rendersFromLaidOutSceneIdenticallyToRecord() throws Exception {
    // Ground-truth oracle: SvgDocument.renderSvg is the untouched record-based internal the engine
    // delegates to, so comparing against it directly (bypassing SvgRenderEngine's own render()
    // entry point) pins that the LaidOutScene -> LayoutResult boundary adapter
    // (LaidOutSceneMapper.toResult) is lossless and the renderer's output is unchanged.
    byte[] input =
        renderInput("fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json");
    SvgRenderEngine.ParsedInput parsed = engine.parseInput(input);
    LaidOutScene scene = LaidOutSceneMapper.toScene(parsed.layoutResult());
    RenderPolicy renderPolicy =
        JsonSupport.objectMapper().treeToValue(parsed.policy(), RenderPolicy.class);
    String expectedSvg = renderSvg(parsed.layoutResult(), parsed.renderMetadata(), renderPolicy);

    RenderResult result = engine.render(scene, parsed.policy(), parsed.renderMetadata()).value();

    assertThat(result.artifacts().get(0).content()).isEqualTo(expectedSvg);
  }

  @Test
  void renderRejectsInvalidPolicyWithPolicyInvalidCode() throws Exception {
    byte[] input =
        invalidPolicyInput(
            "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json");
    SvgRenderEngine.ParsedInput parsed = engine.parseInput(input);

    EngineException failure =
        assertThrows(
            EngineException.class,
            () ->
                engine.render(
                    LaidOutSceneMapper.toScene(parsed.layoutResult()),
                    parsed.policy(),
                    parsed.renderMetadata()));

    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_SVG_POLICY_INVALID");
  }

  @Test
  void parseInputRejectsUnparseableInput() {
    // render publishes no parse-failure envelope: unparseable stdin surfaces as today's raw
    // (non-enveloped) failure, so the parse entry point throws rather than returning a diagnostic.
    assertThatThrownBy(() -> engine.parseInput("not-json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(Exception.class);
  }

  private static JsonNode engineTree(Object value) {
    return JsonSupport.objectMapper()
        .readTree(JsonSupport.objectMapper().writeValueAsString(value));
  }

  private static JsonNode processData(byte[] input) throws Exception {
    PluginResult result =
        Main.executeForTesting(new String[] {"render"}, new String(input, StandardCharsets.UTF_8));
    assertThat(result.exitCode()).describedAs(result.stderr()).isZero();
    return JsonSupport.objectMapper().readTree(result.stdout()).get("data");
  }

  private static byte[] renderInput(String layoutPath, String policyPath) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", fixtureJson(layoutPath));
    input.set("policy", fixtureJson(policyPath));
    return JsonSupport.objectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] invalidPolicyInput(String layoutPath, String policyPath) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", fixtureJson(layoutPath));
    ObjectNode policy = (ObjectNode) fixtureJson(policyPath);
    policy.putObject("style").putObject("node").put("fill", "notacolor#");
    input.set("policy", policy);
    return JsonSupport.objectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8);
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
