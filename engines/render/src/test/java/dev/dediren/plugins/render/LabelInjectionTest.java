package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Hostile-label escaping, exercised end to end. The renderer's {@code Svg.attr}/{@code Svg.text}
 * escaping is unit-testable, but nothing drove an XML/SVG-breakout payload through a full render
 * and checked that (a) it cannot break out of its element and (b) it round-trips back to the exact
 * authored string. A label containing {@code </text><script>…</script>} must reach the SVG only in
 * escaped form. See {@code docs/threat-model.md} (untrusted model text reaching the SVG surface).
 */
class LabelInjectionTest {

  private static final String NODE_LABEL = "</text><script>alert(1)</script>&\"pwn\"]]>";
  private static final String EDGE_LABEL = "</text><tspan>&<x>\"</tspan>";
  private static final String GROUP_LABEL = "<g/>&\"grp\"]]>";

  @Test
  void hostileLabelsAreEscapedAndCannotBreakOut() throws Exception {
    String svg = RenderTestSupport.render(injectionInput());

    // The breakout payload never appears unescaped: no raw <script>, only its escaped form.
    assertThat(svg).doesNotContain("<script>");
    assertThat(svg).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
    // The document still parses: the injected close tags did not terminate their host element.
    SvgAudit.auditStructure(svg);
  }

  @Test
  void hostileLabelsRoundTripToTheExactAuthoredString() throws Exception {
    Document document = SvgAudit.parse(RenderTestSupport.render(injectionInput()));

    assertThat(labelText(document, "data-dediren-node-id", "n1")).isEqualTo(NODE_LABEL);
    assertThat(labelText(document, "data-dediren-edge-id", "e1")).isEqualTo(EDGE_LABEL);
    assertThat(labelText(document, "data-dediren-group-id", "g1")).isEqualTo(GROUP_LABEL);
  }

  private static ObjectNode injectionInput() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", "injection");

    ArrayNode nodes = layout.putArray("nodes");
    addNode(nodes, "n1", 40, 40, 220, 80, NODE_LABEL);
    addNode(nodes, "n2", 360, 40, 180, 80, "Target");

    ArrayNode edges = layout.putArray("edges");
    addEdge(edges, "e1", "n1", "n2", EDGE_LABEL, 260, 80, 360, 80);

    ArrayNode groups = layout.putArray("groups");
    addGroup(groups, "g1", 16, 16, 540, 140, List.of("n1", "n2"), GROUP_LABEL);

    layout.putArray("warnings");
    input.set("policy", RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json"));
    return input;
  }

  private static void addNode(
      ArrayNode nodes, String id, double x, double y, double width, double height, String label) {
    ObjectNode node = nodes.addObject();
    node.put("id", id).put("source_id", id).put("projection_id", id);
    node.put("x", x).put("y", y).put("width", width).put("height", height).put("label", label);
  }

  private static void addEdge(
      ArrayNode edges,
      String id,
      String source,
      String target,
      String label,
      double x1,
      double y1,
      double x2,
      double y2) {
    ObjectNode edge = edges.addObject();
    edge.put("id", id).put("source", source).put("target", target);
    edge.put("source_id", id).put("projection_id", id);
    ArrayNode points = edge.putArray("points");
    points.addObject().put("x", x1).put("y", y1);
    points.addObject().put("x", x2).put("y", y2);
    edge.put("label", label);
  }

  private static void addGroup(
      ArrayNode groups,
      String id,
      double x,
      double y,
      double width,
      double height,
      List<String> members,
      String label) {
    ObjectNode group = groups.addObject();
    group.put("id", id).put("source_id", id).put("projection_id", id);
    group.put("x", x).put("y", y).put("width", width).put("height", height);
    ArrayNode memberIds = group.putArray("members");
    members.forEach(memberIds::add);
    group.put("label", label);
  }

  /** Text content of the first {@code <text>} inside the group carrying {@code attribute=value}. */
  private static String labelText(Document document, String attribute, String value) {
    NodeList groups = document.getElementsByTagName("g");
    for (int index = 0; index < groups.getLength(); index++) {
      Element group = (Element) groups.item(index);
      if (value.equals(group.getAttribute(attribute))) {
        NodeList texts = group.getElementsByTagName("text");
        if (texts.getLength() > 0) {
          return texts.item(0).getTextContent();
        }
      }
    }
    throw new AssertionError("no <text> found in group with " + attribute + "=" + value);
  }
}
