package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Degenerate inputs must still produce a valid, structurally sound SVG rather than a crash or an
 * empty/collapsed canvas: no nodes, a single node, and disconnected components. The empty-layout
 * page-rect fallback in {@code Geometry.svgBounds} exists but had no end-to-end coverage.
 */
class DegenerateRenderTest {

  @Test
  void emptyLayoutRendersAValidNonEmptySvg() throws Exception {
    String svg = RenderTestSupport.render(layout(0));

    assertThat(svg).contains("<svg");
    Document document = SvgAudit.parse(svg);
    assertThat(document.getDocumentElement().getAttribute("viewBox")).isNotBlank();
    SvgAudit.auditStructure(svg);
  }

  @Test
  void singleNodeLayoutRendersThatNode() throws Exception {
    String svg = RenderTestSupport.render(layout(1));

    assertThat(svg).contains("data-dediren-node-id=\"n0\"", ">Node 0<");
    SvgAudit.auditStructure(svg);
  }

  @Test
  void disconnectedComponentsBothRender() throws Exception {
    // Two nodes, no edges: the packer must place both without overlap or flinging one off-canvas.
    String svg = RenderTestSupport.render(layout(2));

    assertThat(svg).contains("data-dediren-node-id=\"n0\"", "data-dediren-node-id=\"n1\"");
    SvgAudit.auditStructure(svg);
  }

  private static ObjectNode layout(int nodeCount) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v1");
    layout.put("view_id", "degenerate");
    ArrayNode nodes = layout.putArray("nodes");
    for (int index = 0; index < nodeCount; index++) {
      ObjectNode node = nodes.addObject();
      String id = "n" + index;
      node.put("id", id).put("source_id", id).put("projection_id", id);
      node.put("x", 40 + index * 240).put("y", 40).put("width", 180).put("height", 80);
      node.put("label", "Node " + index);
    }
    layout.putArray("edges");
    layout.putArray("groups");
    layout.putArray("warnings");
    input.set("policy", RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json"));
    return input;
  }
}
