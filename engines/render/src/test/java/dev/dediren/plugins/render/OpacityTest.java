package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Fill/stroke opacity is a free presentation dimension, so the render policy exposes {@code
 * fill_opacity}/{@code stroke_opacity} on nodes, groups, and the background, and {@code
 * stroke_opacity} on edges. Node opacity is applied by wrapping the shape output so it reaches
 * every notation's shape (generic, ArchiMate, UML) without touching each shape builder; groups,
 * edges, and the background carry the attributes directly.
 */
class OpacityTest {

  @Test
  void nodeFillAndStrokeOpacityAreEmitted() throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(
            "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json", null);
    ObjectNode client =
        ((ObjectNode) input.get("policy"))
            .putObject("style")
            .putObject("node_overrides")
            .putObject("client");
    client.put("fill_opacity", 0.5).put("stroke_opacity", 0.3);

    String svg = RenderTestSupport.render(input);

    assertThat(svg).contains("fill-opacity=\"0.5\"").contains("stroke-opacity=\"0.3\"");
  }

  @Test
  void edgeStrokeOpacityIsEmitted() throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(
            "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json", null);
    ((ObjectNode) input.get("policy"))
        .putObject("style")
        .putObject("edge_overrides")
        .putObject("client-calls-api")
        .put("stroke_opacity", 0.4);

    String svg = RenderTestSupport.render(input);

    assertThat(svg).contains("stroke-opacity=\"0.4\"");
  }

  @Test
  void backgroundFillOpacityIsEmitted() throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(
            "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json", null);
    ((ObjectNode) input.get("policy"))
        .putObject("style")
        .putObject("background")
        .put("fill_opacity", 0.6);

    String svg = RenderTestSupport.render(input);

    assertThat(svg).contains("fill-opacity=\"0.6\"");
  }

  @Test
  void groupFillOpacityIsEmitted() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", "g");
    ArrayNode nodes = layout.putArray("nodes");
    ObjectNode n1 = nodes.addObject();
    n1.put("id", "n1").put("source_id", "n1").put("projection_id", "n1");
    n1.put("x", 40).put("y", 40).put("width", 120).put("height", 60).put("label", "N1");
    layout.putArray("edges");
    ObjectNode group = layout.putArray("groups").addObject();
    group.put("id", "g1").put("source_id", "g1").put("projection_id", "g1");
    group.put("x", 16).put("y", 16).put("width", 200).put("height", 120).put("label", "G");
    group.putArray("members").add("n1");
    layout.putArray("warnings");

    ObjectNode policy =
        (ObjectNode) RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json");
    policy
        .putObject("style")
        .putObject("group_overrides")
        .putObject("g1")
        .put("fill_opacity", 0.25);
    input.set("policy", policy);

    String svg = RenderTestSupport.render(input);

    assertThat(svg).contains("fill-opacity=\"0.25\"");
  }
}
