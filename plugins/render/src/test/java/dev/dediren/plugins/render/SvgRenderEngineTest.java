package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import dev.dediren.engine.EngineResult;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The typed {@link SvgRenderEngine} seam must produce exactly what the process {@code Main} emits:
 * the engine result JSON-equals the {@code data} of {@link Main#executeForTesting}, and the
 * published render diagnostics throw {@link EngineException} with the same code and exit code. The
 * unparseable-input case reproduces today's raw (non-enveloped) parse failure through the engine's
 * parse entry point.
 */
class SvgRenderEngineTest {
  private final SvgRenderEngine engine = new SvgRenderEngine();

  @Test
  void idIsRender() {
    assertThat(engine.id()).isEqualTo("render");
  }

  @Test
  void renderEqualsProcessData() throws Exception {
    byte[] input =
        renderInput("fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json");

    SvgRenderEngine.ParsedInput parsed = engine.parseInput(input);
    EngineResult<?> result =
        engine.render(parsed.layoutResult(), parsed.policy(), parsed.renderMetadata());

    assertThat(engineTree(result.value())).isEqualTo(processData(input));
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
            () -> engine.render(parsed.layoutResult(), parsed.policy(), parsed.renderMetadata()));

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
    policy.put("interactive", "bogus");
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
