package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Scale soundness: a large graph (150 nodes, a chained edge set, and a dozen groups) must still
 * render to a structurally sound SVG. Exercises bounds computation, id uniqueness, and reference
 * resolution at a size no curated fixture reaches — the point where a subtle off-by-one in the
 * viewBox math or an id collision would first surface.
 */
class StressScaleTest {

  private static final int NODES = 150;
  private static final int COLUMNS = 15;

  @Test
  void largeGraphRendersStructurallySound() throws Exception {
    String svg = RenderTestSupport.render(largeGraph());

    Document document = SvgAudit.parse(svg);
    SvgAudit.auditStructure(svg);
    assertThat(document.getElementsByTagName("*").getLength())
        .as("a 150-node graph produces a substantial element tree")
        .isGreaterThan(NODES);
  }

  private static ObjectNode largeGraph() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", "scale");

    ArrayNode nodes = layout.putArray("nodes");
    for (int index = 0; index < NODES; index++) {
      int column = index % COLUMNS;
      int row = index / COLUMNS;
      ObjectNode node = nodes.addObject();
      String id = "n" + index;
      node.put("id", id).put("source_id", id).put("projection_id", id);
      node.put("x", 40 + column * 180).put("y", 40 + row * 110);
      node.put("width", 140).put("height", 70).put("label", "Node " + index);
    }

    ArrayNode edges = layout.putArray("edges");
    for (int index = 0; index < NODES - 1; index++) {
      String id = "e" + index;
      ObjectNode edge = edges.addObject();
      edge.put("id", id).put("source", "n" + index).put("target", "n" + (index + 1));
      edge.put("source_id", id).put("projection_id", id);
      ArrayNode points = edge.putArray("points");
      points
          .addObject()
          .put("x", 40 + (index % COLUMNS) * 180 + 140)
          .put("y", 75 + (index / COLUMNS) * 110);
      points
          .addObject()
          .put("x", 40 + ((index + 1) % COLUMNS) * 180)
          .put("y", 75 + ((index + 1) / COLUMNS) * 110);
      edge.put("label", index % 5 == 0 ? "uses" : "");
    }

    ArrayNode groups = layout.putArray("groups");
    for (int row = 0; row < NODES / COLUMNS; row++) {
      String id = "g" + row;
      ObjectNode group = groups.addObject();
      group.put("id", id).put("source_id", id).put("projection_id", id);
      group
          .put("x", 24)
          .put("y", 24 + row * 110)
          .put("width", COLUMNS * 180 + 8)
          .put("height", 102);
      ArrayNode members = group.putArray("members");
      members.add("n" + (row * COLUMNS));
      group.put("label", "Row " + row);
    }

    layout.putArray("warnings");
    input.set("policy", RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json"));
    return input;
  }
}
