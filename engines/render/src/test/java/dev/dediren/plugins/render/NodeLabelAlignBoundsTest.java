package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code label_align: "start"/"end"} moves the rendered {@code <text>} off-center, but the document
 * bounds ({@code SvgDocument#svgBounds} via {@code NodeLabels#nodeLabelBoxes}) used to always
 * centre the label box regardless. On a node whose label is wider than the node, the two disagreed
 * about which side the label actually overflows, so the viewBox grew on the wrong side and the
 * label was clipped. These tests render a node with an unbreakable label wider than its box and
 * check that the emitted text's actual ink — reconstructed from its {@code x}, {@code text-anchor},
 * and pinned {@code textLength} — lands fully inside the viewBox the bounds path computed.
 */
class NodeLabelAlignBoundsTest {

  // A single unbreakable token (no whitespace, no lowercase-to-uppercase transition) so the wrap
  // algorithm cannot split it: it must overflow the node's width as one line, exactly the scenario
  // where the render and bounds paths disagreeing becomes visible.
  private static final String UNBREAKABLE_LABEL = "unbreakablylongsingletokenlabelthatoverflows";

  @Test
  void endAlignedOverflowingLabelStaysInsideTheViewBox() throws Exception {
    Document document = SvgAudit.parse(RenderTestSupport.render(input("end")));

    Element text = firstNodeText(document, "wide");
    assertThat(text.getAttribute("text-anchor")).isEqualTo("end");

    double[] viewBox = viewBox(document);
    double anchorX = Double.parseDouble(text.getAttribute("x"));
    double textLength = Double.parseDouble(text.getAttribute("textLength"));
    // An end-anchored label's ink extends leftward from the anchor.
    double inkMinX = anchorX - textLength;
    double inkMaxX = anchorX;

    assertOverflowsTheNode(document, "wide", inkMinX, inkMaxX, /* overflowsLeft= */ true);
    assertThat(inkMinX).as("label ink left edge inside viewBox").isGreaterThanOrEqualTo(viewBox[0]);
    assertThat(inkMaxX).as("label ink right edge inside viewBox").isLessThanOrEqualTo(viewBox[1]);
  }

  @Test
  void startAlignedOverflowingLabelStaysInsideTheViewBox() throws Exception {
    Document document = SvgAudit.parse(RenderTestSupport.render(input("start")));

    Element text = firstNodeText(document, "wide");
    assertThat(text.getAttribute("text-anchor")).isEqualTo("start");

    double[] viewBox = viewBox(document);
    double anchorX = Double.parseDouble(text.getAttribute("x"));
    double textLength = Double.parseDouble(text.getAttribute("textLength"));
    // A start-anchored label's ink extends rightward from the anchor.
    double inkMinX = anchorX;
    double inkMaxX = anchorX + textLength;

    assertOverflowsTheNode(document, "wide", inkMinX, inkMaxX, /* overflowsLeft= */ false);
    assertThat(inkMinX).as("label ink left edge inside viewBox").isGreaterThanOrEqualTo(viewBox[0]);
    assertThat(inkMaxX).as("label ink right edge inside viewBox").isLessThanOrEqualTo(viewBox[1]);
  }

  /**
   * Confirms the fixture actually exercises the overflow case (label wider than the node), and that
   * the overflow lands on the side the given {@code label_align} implies — otherwise the viewBox
   * assertion above would pass vacuously.
   */
  private static void assertOverflowsTheNode(
      Document document, String nodeId, double inkMinX, double inkMaxX, boolean overflowsLeft) {
    Element rect = firstNodeRect(document, nodeId);
    double nodeMinX = Double.parseDouble(rect.getAttribute("x"));
    double nodeMaxX = nodeMinX + Double.parseDouble(rect.getAttribute("width"));
    if (overflowsLeft) {
      assertThat(inkMinX).as("label overflows past the node's left edge").isLessThan(nodeMinX);
    } else {
      assertThat(inkMaxX).as("label overflows past the node's right edge").isGreaterThan(nodeMaxX);
    }
  }

  private static ObjectNode input(String labelAlign) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", "label-align-bounds");

    ArrayNode nodes = layout.putArray("nodes");
    ObjectNode node = nodes.addObject();
    node.put("id", "wide").put("source_id", "wide").put("projection_id", "wide");
    node.put("x", 200).put("y", 200).put("width", 100).put("height", 60);
    node.put("label", UNBREAKABLE_LABEL);

    layout.putArray("edges");
    layout.putArray("groups");
    layout.putArray("warnings");

    ObjectNode policy =
        (ObjectNode) RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json");
    policy
        .putObject("style")
        .putObject("node_overrides")
        .putObject("wide")
        .put("label_align", labelAlign);
    input.set("policy", policy);
    return input;
  }

  private static Element firstNodeText(Document document, String nodeId) {
    Element group = nodeGroup(document, nodeId);
    NodeList texts = group.getElementsByTagName("text");
    if (texts.getLength() == 0) {
      throw new AssertionError("no <text> found in node group " + nodeId);
    }
    return (Element) texts.item(0);
  }

  private static Element firstNodeRect(Document document, String nodeId) {
    Element group = nodeGroup(document, nodeId);
    NodeList rects = group.getElementsByTagName("rect");
    if (rects.getLength() == 0) {
      throw new AssertionError("no <rect> found in node group " + nodeId);
    }
    return (Element) rects.item(0);
  }

  private static Element nodeGroup(Document document, String nodeId) {
    NodeList groups = document.getElementsByTagName("g");
    for (int index = 0; index < groups.getLength(); index++) {
      Element group = (Element) groups.item(index);
      if (nodeId.equals(group.getAttribute("data-dediren-node-id"))) {
        return group;
      }
    }
    throw new AssertionError("no node group with id " + nodeId);
  }

  private static double[] viewBox(Document document) {
    String[] parts = document.getDocumentElement().getAttribute("viewBox").trim().split("\\s+");
    double minX = Double.parseDouble(parts[0]);
    double width = Double.parseDouble(parts[2]);
    return new double[] {minX, minX + width};
  }
}
