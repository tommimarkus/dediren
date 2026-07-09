package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.engine.EngineException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Node and group fills can be gradients. The renderer emits an inline {@code <linearGradient>} /
 * {@code <radialGradient>} with a deterministic id and points the shape/rect fill at {@code
 * url(#id)}; the shape builders are untouched (the resolved fill is swapped to the reference).
 */
class GradientTest {

  private final SvgRenderEngine engine = new SvgRenderEngine();

  @Test
  void nodeLinearGradientEmitsGradientAndUrlFill() throws Exception {
    ObjectNode input = defaultInput();
    ObjectNode client =
        ((ObjectNode) input.get("policy"))
            .putObject("style")
            .putObject("node_overrides")
            .putObject("client");
    linearGradient(client.putObject("fill_gradient"));

    String svg = RenderTestSupport.render(input);

    assertThat(svg)
        .contains("<linearGradient id=\"node-fill-client\"")
        .contains("<stop offset=\"0\" stop-color=\"#ff0000\"")
        .contains("fill=\"url(#node-fill-client)\"");
    SvgAudit.auditStructure(svg);
  }

  @Test
  void nodeRadialGradientEmitsRadialElement() throws Exception {
    ObjectNode input = defaultInput();
    ObjectNode gradient =
        ((ObjectNode) input.get("policy"))
            .putObject("style")
            .putObject("node_overrides")
            .putObject("client")
            .putObject("fill_gradient");
    gradient.put("type", "radial");
    ArrayNode stops = gradient.putArray("stops");
    stops.addObject().put("offset", 0).put("color", "#ffffff");
    stops.addObject().put("offset", 1).put("color", "#000000");

    String svg = RenderTestSupport.render(input);

    assertThat(svg).contains("<radialGradient id=\"node-fill-client\"");
  }

  @Test
  void groupGradientEmitsGradientAndUrlFill() throws Exception {
    ObjectNode input = groupLayoutInput();
    linearGradient(
        ((ObjectNode) input.get("policy"))
            .putObject("style")
            .putObject("group_overrides")
            .putObject("g1")
            .putObject("fill_gradient"));

    String svg = RenderTestSupport.render(input);

    assertThat(svg)
        .contains("<linearGradient id=\"group-fill-g1\"")
        .contains("fill=\"url(#group-fill-g1)\"");
  }

  @Test
  void gradientWithNoStopsIsRejected() throws Exception {
    ObjectNode input = defaultInput();
    ObjectNode gradient =
        ((ObjectNode) input.get("policy"))
            .putObject("style")
            .putObject("node_overrides")
            .putObject("client")
            .putObject("fill_gradient");
    gradient.put("type", "linear");
    gradient.putArray("stops");

    byte[] bytes =
        JsonSupport.objectMapper().writeValueAsString(input).getBytes(StandardCharsets.UTF_8);
    SvgRenderEngine.ParsedInput parsed = engine.parseInput(bytes);
    EngineException failure =
        assertThrows(
            EngineException.class,
            () -> engine.render(parsed.layoutResult(), parsed.policy(), parsed.renderMetadata()));

    assertThat(failure.diagnostics().get(0).code()).isEqualTo("DEDIREN_SVG_POLICY_INVALID");
  }

  private static void linearGradient(ObjectNode gradient) {
    gradient.put("type", "linear").put("angle", 90);
    ArrayNode stops = gradient.putArray("stops");
    stops.addObject().put("offset", 0).put("color", "#ff0000");
    stops.addObject().put("offset", 1).put("color", "#0000ff");
  }

  private static ObjectNode defaultInput() throws Exception {
    return RenderTestSupport.fixtureInput(
        "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json", null);
  }

  private static ObjectNode groupLayoutInput() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", "g");
    ObjectNode n1 = layout.putArray("nodes").addObject();
    n1.put("id", "n1").put("source_id", "n1").put("projection_id", "n1");
    n1.put("x", 40).put("y", 40).put("width", 120).put("height", 60).put("label", "N1");
    layout.putArray("edges");
    ObjectNode group = layout.putArray("groups").addObject();
    group.put("id", "g1").put("source_id", "g1").put("projection_id", "g1");
    group.put("x", 16).put("y", 16).put("width", 200).put("height", 120).put("label", "G");
    group.putArray("members").add("n1");
    layout.putArray("warnings");
    input.set("policy", RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json"));
    return input;
  }
}
