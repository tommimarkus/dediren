package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Line style is a free presentation dimension. Edges gain a {@code dotted} preset and a custom
 * {@code dash_pattern}; nodes and group borders gain {@code line_style} (solid/dashed/dotted) and
 * {@code dash_pattern}. A {@code dash_pattern} overrides the preset; the ArchiMate grouping
 * border's historical {@code "3 2"} dash is preserved when no override is given.
 */
class LineStyleTest {

  @Test
  void edgeDottedPresetEmitsDashArray() throws Exception {
    String svg = renderEdgeOverride(o -> o.put("line_style", "dotted"));
    assertThat(svg).contains("stroke-dasharray=\"1 3\"");
  }

  @Test
  void edgeCustomDashPatternWins() throws Exception {
    String svg = renderEdgeOverride(o -> dashPattern(o, 4, 2));
    assertThat(svg).contains("stroke-dasharray=\"4 2\"");
  }

  @Test
  void nodeDashedBorderEmitsDashArray() throws Exception {
    String svg = renderNodeOverride(o -> o.put("line_style", "dashed"));
    assertThat(svg).contains("stroke-dasharray=\"6 4\"");
  }

  @Test
  void nodeCustomDashPatternWins() throws Exception {
    String svg = renderNodeOverride(o -> dashPattern(o, 5, 3));
    assertThat(svg).contains("stroke-dasharray=\"5 3\"");
  }

  @Test
  void groupDashedBorderEmitsDashArray() throws Exception {
    ObjectNode input = groupLayoutInput();
    ObjectNode policy = (ObjectNode) input.get("policy");
    policy
        .putObject("style")
        .putObject("group_overrides")
        .putObject("g1")
        .put("line_style", "dashed");

    String svg = RenderTestSupport.render(input);

    assertThat(svg).contains("stroke-dasharray=\"6 4\"");
  }

  private interface Mutation {
    void apply(ObjectNode override);
  }

  private static void dashPattern(ObjectNode override, double... values) {
    ArrayNode array = override.putArray("dash_pattern");
    for (double value : values) {
      array.add(value);
    }
  }

  private static String renderEdgeOverride(Mutation mutation) throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(
            "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json", null);
    ObjectNode override =
        ((ObjectNode) input.get("policy"))
            .putObject("style")
            .putObject("edge_overrides")
            .putObject("client-calls-api");
    mutation.apply(override);
    return RenderTestSupport.render(input);
  }

  private static String renderNodeOverride(Mutation mutation) throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(
            "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json", null);
    ObjectNode override =
        ((ObjectNode) input.get("policy"))
            .putObject("style")
            .putObject("node_overrides")
            .putObject("client");
    mutation.apply(override);
    return RenderTestSupport.render(input);
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
