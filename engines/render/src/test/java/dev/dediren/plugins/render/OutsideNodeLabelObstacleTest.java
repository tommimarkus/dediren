package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.plugins.render.svg.Svg;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Edge-label placement steps around whatever is already on the page, and a node's label is on the
 * page. {@code Geometry.nodeObstacleBoxes} contributes node <em>rects</em> only, which is the whole
 * story right up until a node's label is drawn outside its rect: an ArchiMate junction centres its
 * label below the circle, and the UML compact controls set theirs diagonally up-left. An edge label
 * could then be placed "clear" of every obstacle and printed straight over one.
 *
 * <p>Unlike the rest of this wave, closing that gap moves a label rather than growing the viewBox —
 * so it is checked by a differential rather than by containment. The same layout renders twice,
 * once with the junction's label empty (nothing outside the node) and once with it set, and the
 * assertions pin both halves of the defect: the placement the renderer chooses when nothing is in
 * the way collides with the junction's label, and the placement it chooses when that label exists
 * does not.
 */
class OutsideNodeLabelObstacleTest {

  private static final String JUNCTION_LABEL = "Handoff";
  private static final String EDGE_LABEL = "ok";

  @Test
  void edgeLabelStepsAroundALabelDrawnOutsideItsJunction() throws Exception {
    Document unlabelled = SvgAudit.parse(RenderTestSupport.render(input("")));
    Document labelled = SvgAudit.parse(RenderTestSupport.render(input(JUNCTION_LABEL)));

    assertThat(directChildren(nodeGroup(unlabelled), "text"))
        .as("the control really does draw no junction label")
        .isEmpty();

    double[] junctionLabel = pinnedTextInk(nodeText(labelled));
    double[] unobstructed = estimatedTextInk(edgeText(unlabelled));
    double[] placed = estimatedTextInk(edgeText(labelled));

    assertThat(overlaps(junctionLabel, unobstructed))
        .as("the fixture is only meaningful if the unobstructed placement collides with the label")
        .isTrue();
    assertThat(placed[0])
        .as("the junction's label moved the edge label somewhere else")
        .isNotEqualTo(unobstructed[0]);
    assertThat(overlaps(junctionLabel, placed))
        .as("the placed edge label prints over the junction's label")
        .isFalse();
  }

  /**
   * A junction whose label sits where the edge label would otherwise go — below and left of the
   * edge's start — while its <em>rect</em> stays clear of that spot, so the only thing that can
   * move the edge label is the label box itself rather than the node box the obstacle model already
   * knew about.
   */
  private static ObjectNode input(String junctionLabel) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", "outside-node-label-obstacle");

    ObjectNode node = layout.putArray("nodes").addObject();
    node.put("id", "j").put("source_id", "j").put("projection_id", "j");
    node.put("x", 95).put("y", 95).put("width", 8).put("height", 8);
    node.put("label", junctionLabel);

    ObjectNode edge = layout.putArray("edges").addObject();
    edge.put("id", "e").put("source", "a").put("target", "b");
    edge.put("source_id", "e").put("projection_id", "e");
    edge.putArray("routing_hints");
    ArrayNode points = edge.putArray("points");
    points.addObject().put("x", 100).put("y", 100);
    points.addObject().put("x", 300).put("y", 100);
    edge.put("label", EDGE_LABEL);

    layout.putArray("groups");
    layout.putArray("warnings");

    ObjectNode policy =
        (ObjectNode) RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json");
    policy
        .putObject("style")
        .putObject("node_overrides")
        .putObject("j")
        .put("decorator", "archimate_and_junction");
    input.set("policy", policy);
    return input;
  }

  /** Ink of a node label, whose emitted {@code textLength} pins the width the viewer will draw. */
  private static double[] pinnedTextInk(Element text) {
    return textInk(text, Double.parseDouble(text.getAttribute("textLength")));
  }

  /**
   * Ink of an edge label, which carries no {@code textLength}, so its width comes from the
   * renderer's own estimator — the measure its placement reserved for it.
   */
  private static double[] estimatedTextInk(Element text) {
    return textInk(
        text,
        Svg.estimateTextWidth(
            text.getTextContent(), Double.parseDouble(text.getAttribute("font-size"))));
  }

  private static double[] textInk(Element text, double width) {
    double anchorX = Double.parseDouble(text.getAttribute("x"));
    double baselineY = Double.parseDouble(text.getAttribute("y"));
    double fontSize = Double.parseDouble(text.getAttribute("font-size"));
    double minX =
        switch (text.getAttribute("text-anchor")) {
          case "end" -> anchorX - width;
          case "middle" -> anchorX - width / 2.0;
          default -> anchorX;
        };
    // Cap height above the baseline, descender below it: the same line box the renderer reserves.
    return new double[] {minX, baselineY - fontSize, minX + width, baselineY + fontSize * 0.25};
  }

  private static boolean overlaps(double[] left, double[] right) {
    return left[0] < right[2] && left[2] > right[0] && left[1] < right[3] && left[3] > right[1];
  }

  private static Element nodeText(Document document) {
    return directChildren(nodeGroup(document), "text").get(0);
  }

  private static Element edgeText(Document document) {
    // With the outline label presentation the same string is written twice, an outline pass under a
    // fill pass, both at the one anchor placement chose.
    return directChildren(groupBy(document, "data-dediren-edge-id", "e"), "text").get(0);
  }

  private static Element nodeGroup(Document document) {
    return groupBy(document, "data-dediren-node-id", "j");
  }

  private static List<Element> directChildren(Element parent, String tag) {
    List<Element> matches = new ArrayList<>();
    NodeList children = parent.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child instanceof Element element && tag.equals(element.getTagName())) {
        matches.add(element);
      }
    }
    return matches;
  }

  private static Element groupBy(Document document, String attribute, String id) {
    NodeList groups = document.getElementsByTagName("g");
    for (int index = 0; index < groups.getLength(); index++) {
      Element group = (Element) groups.item(index);
      if (id.equals(group.getAttribute(attribute))) {
        return group;
      }
    }
    throw new AssertionError("no <g> with " + attribute + "=" + id);
  }
}
