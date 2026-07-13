package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import dev.dediren.ir.LaidOutSceneMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code page} and {@code margin} are two of the three fields render-policy.schema.json marks
 * required, but nothing validated them at runtime: a policy without {@code margin} dereferenced
 * null in {@code SvgBounds.padded} and surfaced as an unstructured {@code DEDIREN_ENGINE_FAILED}
 * crash on the generic path, while the sequence path silently substituted its own defaults — the
 * two renderers disagreed about whether the input was even an error.
 *
 * <p>In a contract-first product an agent decides from stdout JSON alone, so a policy authoring
 * mistake must come back as the published {@code DEDIREN_SVG_POLICY_INVALID} with a path, not as a
 * crash.
 */
class RequiredPolicyFieldsTest {

  private final SvgRenderEngine engine = new SvgRenderEngine();

  @Test
  void aPolicyWithoutMarginIsRejectedAsPolicyInvalid() throws Exception {
    EngineException failure = renderExpectingFailure(policyWithout("margin"));

    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_SVG_POLICY_INVALID");
    assertThat(failure.diagnostics().get(0).message()).contains("margin");
  }

  @Test
  void aPolicyWithoutPageIsRejectedAsPolicyInvalid() throws Exception {
    EngineException failure = renderExpectingFailure(policyWithout("page"));

    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_SVG_POLICY_INVALID");
    assertThat(failure.diagnostics().get(0).message()).contains("page");
  }

  private EngineException renderExpectingFailure(byte[] input) {
    return catchThrowableOfType(
        EngineException.class,
        () -> {
          SvgRenderEngine.ParsedInput parsed = engine.parseInput(input);
          engine.render(
              LaidOutSceneMapper.toScene(parsed.layoutResult()),
              parsed.policy(),
              parsed.renderMetadata());
        });
  }

  private static byte[] policyWithout(String field) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", fixtureJson("fixtures/layout-result/basic.json"));
    ObjectNode policy = (ObjectNode) fixtureJson("fixtures/render-policy/default-svg.json");
    policy.remove(field);
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
