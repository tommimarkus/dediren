package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
 * Spec-preservation guards for the generic {@code shape} field. A shape is only meaningful for
 * notations that do not fix geometry, so the render policy rejects (a) a node style that sets both
 * a {@code shape} and a notation {@code decorator}, and (b) any {@code shape} under the {@code
 * archimate}/{@code uml} semantic profiles — both as {@code DEDIREN_SVG_POLICY_INVALID}. This keeps
 * ArchiMate/UML notation authoritative while generic graphs stay free.
 */
class GenericShapePolicyTest {

  private final SvgRenderEngine engine = new SvgRenderEngine();

  @Test
  void shapeAndDecoratorOnSameNodeStyleIsRejected() throws Exception {
    ObjectNode override = JsonSupport.objectMapper().createObjectNode();
    override.put("shape", "ellipse").put("decorator", "archimate_business_actor");

    EngineException failure = renderExpectingFailure(policyWithClientOverride(null, override));

    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_SVG_POLICY_INVALID");
  }

  @Test
  void shapeUnderArchimateProfileIsRejected() throws Exception {
    ObjectNode override = JsonSupport.objectMapper().createObjectNode();
    override.put("shape", "ellipse");

    EngineException failure =
        renderExpectingFailure(policyWithClientOverride("archimate", override));

    assertThat(failure.exitCode()).isEqualTo(3);
    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_SVG_POLICY_INVALID");
  }

  private EngineException renderExpectingFailure(byte[] input) throws Exception {
    SvgRenderEngine.ParsedInput parsed = engine.parseInput(input);
    return assertThrows(
        EngineException.class,
        () ->
            engine.render(
                LaidOutSceneMapper.toScene(parsed.layoutResult()),
                parsed.policy(),
                parsed.renderMetadata()));
  }

  private static byte[] policyWithClientOverride(String profile, ObjectNode clientOverride)
      throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", fixtureJson("fixtures/layout-result/basic.json"));
    ObjectNode policy = (ObjectNode) fixtureJson("fixtures/render-policy/default-svg.json");
    if (profile != null) {
      policy.put("semantic_profile", profile);
    }
    policy.putObject("style").putObject("node_overrides").set("client", clientOverride);
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
