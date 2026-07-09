package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * The UML sequence renderer is a separate pipeline (it bypasses {@code StyleResolver}), so the free
 * dimensions are threaded through its own {@code NodePaint}/{@code EdgePaint}. Sequence node boxes
 * gain fill/stroke opacity and a {@code line_style} preset; messages and labels gain opacity. The
 * message line style stays semantic (reply → dashed), and per-element label fonts are out of scope.
 */
class SequenceStylingTest {

  @Test
  void lifelineOpacityDashAndMessageOpacityAreEmitted() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set(
        "layout_result",
        RenderTestSupport.fixtureJson("fixtures/layout-result/uml-sequence-basic.json"));
    input.set(
        "render_metadata",
        RenderTestSupport.fixtureJson("fixtures/render-metadata/uml-sequence-basic.json"));

    ObjectNode policy = input.putObject("policy");
    policy.put("render_policy_schema_version", "render-policy.schema.v3");
    policy.put("semantic_profile", "uml");
    policy.putObject("page").put("width", 1200).put("height", 800);
    policy.putObject("margin").put("top", 32).put("right", 32).put("bottom", 32).put("left", 32);
    ObjectNode style = policy.putObject("style");
    style
        .putObject("node_type_overrides")
        .putObject("Lifeline")
        .put("fill_opacity", 0.4)
        .put("line_style", "dashed");
    style.putObject("edge_type_overrides").putObject("Message").put("stroke_opacity", 0.6);

    String svg = RenderTestSupport.render(input);

    assertThat(svg)
        .contains("fill-opacity=\"0.4\"")
        .contains("stroke-dasharray=\"6 4\"")
        .contains("stroke-opacity=\"0.6\"");
    SvgAudit.auditStructure(svg);
  }
}
