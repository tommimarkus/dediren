package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import dev.dediren.plugins.render.svg.Svg;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The viewBox has to hold everything the document draws, not everything the layout laid out.
 *
 * <p>Four kinds of ink used to be drawn without being measured: a group's title, a junction circle
 * whose radius floors above its node's half-extent, the outer half of every stroke, and the extents
 * a line-jump arc and an end marker reach past the route's own vertices. Under the default 32px
 * margin each is invisible — the padding absorbs it. The render-policy schema puts {@code margin}'s
 * minimum at 0, and at 0 each one is a shape sliced off at the diagram edge: a title clipped
 * mid-word, a junction flattened into a D, a border shaved to half width, an arrowhead cut through
 * lengthwise.
 *
 * <p>So every case here renders at {@code margin: 0} and asserts a containment
 * <em>relationship</em> — the ink each element actually lays down, reconstructed from the
 * attributes it was emitted with, falls inside the viewBox — rather than a pixel value a
 * re-baseline would quietly rewrite. Each also asserts that its fixture still exercises its gap
 * (the title really does overrun its group, the circle really is wider than its node), so none of
 * them can pass by never reaching the edge in the first place.
 */
class DocumentBoundsCompletenessTest {

  // The viewBox is emitted at one decimal place, so a containment comparison against it carries up
  // to half of that last place in rounding. Everything here is about whether ink is measured at
  // all, and no gap in this file is a twentieth of a pixel wide.
  private static final double VIEWBOX_ROUNDING = 0.05;

  // A title far wider than the group it names: label_size may be set as high as 96, which turns a
  // sixteen-character group name into roughly 720px of text over a 100px-wide group.
  private static final String WIDE_GROUP_TITLE = "Wide Group Title";
  private static final double GROUP_X = 0.0;
  private static final double GROUP_WIDTH = 100.0;
  private static final double HUGE_LABEL_SIZE = 96.0;

  // 6x6 is below twice the 4.0 radius floor in NodeShapeSupport#archimateJunctionRadius, so the
  // junction's circle comes out strictly wider than the node box the layout gave it.
  private static final double JUNCTION_SIZE = 6.0;

  private static final double THICK_STROKE = 12.0;
  private static final double MARKER_EDGE_STROKE = 10.0;
  private static final double MARKER_EDGE_END_X = 200.0;
  private static final double MARKER_EDGE_Y = 0.0;

  // A horizontal edge that stops just past the vertical edge crossing it. The crossing has to sit
  // strictly inside both segments for a jump to be drawn at all, so the horizontal route has to
  // outreach it — but only by 0.5px, which leaves the jump's own arc as the right-most ink.
  private static final double JUMP_CROSSING_X = 100.0;
  private static final double HORIZONTAL_EDGE_END_X = 100.5;
  private static final double JUMP_EDGE_STROKE = 1.0;

  @Test
  void groupTitleOverrunningItsGroupStaysInsideTheViewBox() throws Exception {
    Document document = SvgAudit.parse(RenderTestSupport.render(groupInput(null)));

    Element title = groupTitle(document);
    assertThat(title.getAttribute("text-anchor"))
        .as("a start-aligned title takes SVG's default anchor")
        .isEmpty();

    double[] viewBox = viewBox(document);
    // A start-anchored label's ink runs rightward from the anchor.
    double inkMaxX = Double.parseDouble(title.getAttribute("x")) + titleWidth(title);

    assertThat(inkMaxX)
        .as("the fixture's title really does overrun its group's right edge")
        .isGreaterThan(GROUP_X + GROUP_WIDTH);
    assertThat(inkMaxX)
        .as("title ink right edge inside viewBox")
        .isLessThanOrEqualTo(viewBox[2] + VIEWBOX_ROUNDING);
  }

  @Test
  void endAlignedGroupTitleOverrunsLeftAndStaysInsideTheViewBox() throws Exception {
    Document document = SvgAudit.parse(RenderTestSupport.render(groupInput("end")));

    Element title = groupTitle(document);
    assertThat(title.getAttribute("text-anchor")).isEqualTo("end");

    double[] viewBox = viewBox(document);
    // An end-anchored label's ink runs leftward. Measuring it as if it were centred — the shape
    // the node lane's label_align drift took — grows the viewBox on the wrong side by half a title.
    double inkMinX = Double.parseDouble(title.getAttribute("x")) - titleWidth(title);

    assertThat(inkMinX)
        .as("the fixture's title really does overrun its group's left edge")
        .isLessThan(GROUP_X);
    assertThat(inkMinX)
        .as("title ink left edge inside viewBox")
        .isGreaterThanOrEqualTo(viewBox[0] - VIEWBOX_ROUNDING);
  }

  @Test
  void junctionCircleWiderThanItsNodeStaysInsideTheViewBox() throws Exception {
    Document document = SvgAudit.parse(RenderTestSupport.render(junctionInput()));

    Element circle = firstByTag(document, "circle");
    double centerX = Double.parseDouble(circle.getAttribute("cx"));
    double centerY = Double.parseDouble(circle.getAttribute("cy"));
    double radius = Double.parseDouble(circle.getAttribute("r"));
    double half = Double.parseDouble(circle.getAttribute("stroke-width")) / 2.0;

    assertThat(radius)
        .as("the fixture's junction circle really is wider than the node box it replaces")
        .isGreaterThan(JUNCTION_SIZE / 2.0);

    double[] viewBox = viewBox(document);
    assertThat(centerX - radius - half)
        .as("circle left edge inside viewBox")
        .isGreaterThanOrEqualTo(viewBox[0] - VIEWBOX_ROUNDING);
    assertThat(centerY - radius - half)
        .as("circle top edge inside viewBox")
        .isGreaterThanOrEqualTo(viewBox[1] - VIEWBOX_ROUNDING);
    assertThat(centerX + radius + half)
        .as("circle right edge inside viewBox")
        .isLessThanOrEqualTo(viewBox[2] + VIEWBOX_ROUNDING);
    assertThat(centerY + radius + half)
        .as("circle bottom edge inside viewBox")
        .isLessThanOrEqualTo(viewBox[3] + VIEWBOX_ROUNDING);
  }

  @Test
  void nodeStrokeOuterHalfStaysInsideTheViewBox() throws Exception {
    Document document = SvgAudit.parse(RenderTestSupport.render(thickNodeInput()));

    Element rect = firstDirectChild(groupById(document, "n"), "rect");
    double x = Double.parseDouble(rect.getAttribute("x"));
    double y = Double.parseDouble(rect.getAttribute("y"));
    double width = Double.parseDouble(rect.getAttribute("width"));
    double height = Double.parseDouble(rect.getAttribute("height"));
    // SVG centres a stroke on the path it outlines, so half of it lies outside the rect's geometry.
    double half = Double.parseDouble(rect.getAttribute("stroke-width")) / 2.0;
    assertThat(half).as("the fixture's border really is thick enough to notice").isGreaterThan(1.0);

    double[] viewBox = viewBox(document);
    assertThat(viewBox[0])
        .as("at margin 0 the viewBox can only reach past the geometry for the stroke")
        .isLessThan(x);
    assertThat(x - half)
        .as("stroke left edge inside viewBox")
        .isGreaterThanOrEqualTo(viewBox[0] - VIEWBOX_ROUNDING);
    assertThat(y - half)
        .as("stroke top edge inside viewBox")
        .isGreaterThanOrEqualTo(viewBox[1] - VIEWBOX_ROUNDING);
    assertThat(x + width + half)
        .as("stroke right edge inside viewBox")
        .isLessThanOrEqualTo(viewBox[2] + VIEWBOX_ROUNDING);
    assertThat(y + height + half)
        .as("stroke bottom edge inside viewBox")
        .isLessThanOrEqualTo(viewBox[3] + VIEWBOX_ROUNDING);
  }

  @Test
  void endMarkerViewportStaysInsideTheViewBox() throws Exception {
    Document document = SvgAudit.parse(RenderTestSupport.render(markerEdgeInput()));

    Element marker = firstByTag(document, "marker");
    double viewportWidth = Double.parseDouble(marker.getAttribute("markerWidth"));
    double viewportHeight = Double.parseDouble(marker.getAttribute("markerHeight"));
    double refX = Double.parseDouble(marker.getAttribute("refX"));
    double refY = Double.parseDouble(marker.getAttribute("refY"));
    double strokeWidth = Double.parseDouble(routePath(document, "e").getAttribute("stroke-width"));

    // markerUnits is unset, so the SVG default of strokeWidth applies and the viewport is that many
    // stroke widths across — treating it as a flat 10x10 box measures a 1px edge's marker, whatever
    // the edge. This route runs due east, so the viewport is unrotated and refX/refY land on the
    // endpoint.
    double tipX = MARKER_EDGE_END_X + (viewportWidth - refX) * strokeWidth;
    double topY = MARKER_EDGE_Y - refY * strokeWidth;
    double bottomY = MARKER_EDGE_Y + (viewportHeight - refY) * strokeWidth;

    assertThat(tipX)
        .as("the fixture's marker really does reach past the stroked end of its route")
        .isGreaterThan(MARKER_EDGE_END_X + strokeWidth / 2.0);

    double[] viewBox = viewBox(document);
    assertThat(tipX)
        .as("marker tip inside viewBox")
        .isLessThanOrEqualTo(viewBox[2] + VIEWBOX_ROUNDING);
    assertThat(topY)
        .as("marker top inside viewBox")
        .isGreaterThanOrEqualTo(viewBox[1] - VIEWBOX_ROUNDING);
    assertThat(bottomY)
        .as("marker bottom inside viewBox")
        .isLessThanOrEqualTo(viewBox[3] + VIEWBOX_ROUNDING);
  }

  @Test
  void lineJumpArcStaysInsideTheViewBox() throws Exception {
    Document document = SvgAudit.parse(RenderTestSupport.render(lineJumpInput()));

    Element jumping = routePath(document, "v");
    assertThat(jumping.getAttribute("d"))
        .as("the fixture's second edge really does jump over the first")
        .contains(" Q ");

    double[] ink = pathBounds(jumping.getAttribute("d"));
    double half = JUMP_EDGE_STROKE / 2.0;

    assertThat(ink[2])
        .as("the jump's arc really does bulge past every route vertex in the document")
        .isGreaterThan(HORIZONTAL_EDGE_END_X);

    double[] viewBox = viewBox(document);
    assertThat(viewBox[2])
        .as("at margin 0 the viewBox can only reach past the routes for the arc and its stroke")
        .isGreaterThan(HORIZONTAL_EDGE_END_X + half);
    assertThat(ink[2] + half)
        .as("arc right edge inside viewBox")
        .isLessThanOrEqualTo(viewBox[2] + VIEWBOX_ROUNDING);
    assertThat(ink[0] - half)
        .as("arc left edge inside viewBox")
        .isGreaterThanOrEqualTo(viewBox[0] - VIEWBOX_ROUNDING);
  }

  private static ObjectNode groupInput(String labelAlign) throws Exception {
    ObjectNode input = zeroMarginInput("group-title-bounds");
    ObjectNode layout = (ObjectNode) input.get("layout_result");

    ObjectNode node = ((ArrayNode) layout.get("nodes")).addObject();
    node.put("id", "n").put("source_id", "n").put("projection_id", "n");
    node.put("x", 10).put("y", 60).put("width", 60).put("height", 40).put("label", "n");

    ObjectNode group = ((ArrayNode) layout.get("groups")).addObject();
    group.put("id", "g").put("source_id", "g").put("projection_id", "g");
    group.put("x", GROUP_X).put("y", 0).put("width", GROUP_WIDTH).put("height", 200);
    group.putArray("members").add("n");
    group.put("label", WIDE_GROUP_TITLE);

    ObjectNode override =
        ((ObjectNode) input.get("policy"))
            .putObject("style")
            .putObject("group_overrides")
            .putObject("g");
    override.put("label_size", HUGE_LABEL_SIZE);
    if (labelAlign != null) {
      override.put("label_align", labelAlign);
    }
    return input;
  }

  private static ObjectNode junctionInput() throws Exception {
    ObjectNode input = zeroMarginInput("junction-bounds");
    ObjectNode node = ((ArrayNode) input.get("layout_result").get("nodes")).addObject();
    node.put("id", "j").put("source_id", "j").put("projection_id", "j");
    node.put("x", 100).put("y", 100);
    node.put("width", JUNCTION_SIZE).put("height", JUNCTION_SIZE).put("label", "j");

    ((ObjectNode) input.get("policy"))
        .putObject("style")
        .putObject("node_overrides")
        .putObject("j")
        .put("decorator", "archimate_and_junction");
    return input;
  }

  private static ObjectNode thickNodeInput() throws Exception {
    ObjectNode input = zeroMarginInput("node-stroke-bounds");
    ObjectNode node = ((ArrayNode) input.get("layout_result").get("nodes")).addObject();
    node.put("id", "n").put("source_id", "n").put("projection_id", "n");
    node.put("x", 0).put("y", 0).put("width", 100).put("height", 60).put("label", "n");

    ((ObjectNode) input.get("policy"))
        .putObject("style")
        .putObject("node")
        .put("stroke_width", THICK_STROKE);
    return input;
  }

  private static ObjectNode markerEdgeInput() throws Exception {
    ObjectNode input = zeroMarginInput("marker-bounds");
    addEdge(
        (ObjectNode) input.get("layout_result"),
        "e",
        new double[][] {{0.0, MARKER_EDGE_Y}, {MARKER_EDGE_END_X, MARKER_EDGE_Y}});

    ((ObjectNode) input.get("policy"))
        .putObject("style")
        .putObject("edge")
        .put("stroke_width", MARKER_EDGE_STROKE);
    return input;
  }

  private static ObjectNode lineJumpInput() throws Exception {
    ObjectNode input = zeroMarginInput("line-jump-bounds");
    ObjectNode layout = (ObjectNode) input.get("layout_result");
    // Order matters: a jump is drawn on the edge routed second, over the one routed first.
    addEdge(layout, "h", new double[][] {{0.0, 100.0}, {HORIZONTAL_EDGE_END_X, 100.0}});
    addEdge(layout, "v", new double[][] {{JUMP_CROSSING_X, 0.0}, {JUMP_CROSSING_X, 200.0}});

    // No markers: an arrowhead at a route end reaches further out than the arc does, and this case
    // is about the arc.
    ((ObjectNode) input.get("policy"))
        .putObject("style")
        .putObject("edge")
        .put("stroke_width", JUMP_EDGE_STROKE)
        .put("marker_end", "none");
    return input;
  }

  private static void addEdge(ObjectNode layout, String id, double[][] points) {
    ObjectNode edge = ((ArrayNode) layout.get("edges")).addObject();
    edge.put("id", id).put("source", id + "-s").put("target", id + "-t");
    edge.put("source_id", id).put("projection_id", id);
    edge.putArray("routing_hints");
    ArrayNode routed = edge.putArray("points");
    for (double[] point : points) {
      routed.addObject().put("x", point[0]).put("y", point[1]);
    }
    // An empty label keeps edge-label placement out of the bounds under test.
    edge.put("label", "");
  }

  /** A render input with an empty layout and a margin-free copy of the default policy. */
  private static ObjectNode zeroMarginInput(String viewId) throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", viewId);
    layout.putArray("nodes");
    layout.putArray("edges");
    layout.putArray("groups");
    layout.putArray("warnings");

    ObjectNode policy =
        (ObjectNode) RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json");
    policy.putObject("margin").put("top", 0).put("right", 0).put("bottom", 0).put("left", 0);
    input.set("policy", policy);
    return input;
  }

  /**
   * The title's ink width. A group title carries no {@code textLength} pin to read back, so its
   * width comes from the renderer's own estimator — the same measure the bounds pass reserves for
   * it. That makes these tests ask whether the title is measured at all, and against which anchor,
   * not how accurate the estimate is; {@code LabelBoxMeasurementTest} owns the latter.
   */
  private static double titleWidth(Element title) {
    return Svg.estimateTextWidth(
        title.getTextContent(), Double.parseDouble(title.getAttribute("font-size")));
  }

  /** Ink bounds of an M/L/Q path, sampling each quadratic (t=0.5 lands on a jump arc's apex). */
  private static double[] pathBounds(String data) {
    String[] tokens = data.trim().split("\\s+");
    double[] box = {
      Double.POSITIVE_INFINITY,
      Double.POSITIVE_INFINITY,
      Double.NEGATIVE_INFINITY,
      Double.NEGATIVE_INFINITY
    };
    double currentX = 0.0;
    double currentY = 0.0;
    int index = 0;
    while (index < tokens.length) {
      String command = tokens[index++];
      if ("M".equals(command) || "L".equals(command)) {
        currentX = Double.parseDouble(tokens[index++]);
        currentY = Double.parseDouble(tokens[index++]);
        include(box, currentX, currentY);
      } else if ("Q".equals(command)) {
        double controlX = Double.parseDouble(tokens[index++]);
        double controlY = Double.parseDouble(tokens[index++]);
        double endX = Double.parseDouble(tokens[index++]);
        double endY = Double.parseDouble(tokens[index++]);
        for (int step = 0; step <= 20; step++) {
          double t = step / 20.0;
          double inverse = 1.0 - t;
          include(
              box,
              inverse * inverse * currentX + 2.0 * inverse * t * controlX + t * t * endX,
              inverse * inverse * currentY + 2.0 * inverse * t * controlY + t * t * endY);
        }
        currentX = endX;
        currentY = endY;
      } else {
        throw new AssertionError("unexpected path command " + command + " in " + data);
      }
    }
    return box;
  }

  private static void include(double[] box, double x, double y) {
    box[0] = Math.min(box[0], x);
    box[1] = Math.min(box[1], y);
    box[2] = Math.max(box[2], x);
    box[3] = Math.max(box[3], y);
  }

  private static Element groupTitle(Document document) {
    return firstDirectChild(groupById(document, "g"), "text");
  }

  /**
   * The route path of an edge: its direct {@code <path>} child. The marker definitions and the line
   * jump masks contribute {@code <path>} elements of their own, nested a level deeper and carrying
   * quite different stroke widths.
   */
  private static Element routePath(Document document, String edgeId) {
    return firstDirectChild(groupById(document, edgeId), "path");
  }

  private static Element firstDirectChild(Element parent, String tag) {
    NodeList children = parent.getChildNodes();
    for (int index = 0; index < children.getLength(); index++) {
      Node child = children.item(index);
      if (child instanceof Element element && tag.equals(element.getTagName())) {
        return element;
      }
    }
    throw new AssertionError("no direct <" + tag + "> child of " + parent.getTagName());
  }

  private static Element groupById(Document document, String id) {
    NodeList groups = document.getElementsByTagName("g");
    for (int index = 0; index < groups.getLength(); index++) {
      Element group = (Element) groups.item(index);
      if (id.equals(group.getAttribute("data-dediren-group-id"))
          || id.equals(group.getAttribute("data-dediren-node-id"))
          || id.equals(group.getAttribute("data-dediren-edge-id"))) {
        return group;
      }
    }
    throw new AssertionError("no <g> carrying the id " + id);
  }

  private static Element firstByTag(Document document, String tag) {
    NodeList elements = document.getElementsByTagName(tag);
    if (elements.getLength() == 0) {
      throw new AssertionError("no <" + tag + "> in the document");
    }
    return (Element) elements.item(0);
  }

  /** The viewBox as {@code {minX, minY, maxX, maxY}}. */
  private static double[] viewBox(Document document) {
    String[] parts = document.getDocumentElement().getAttribute("viewBox").trim().split("\\s+");
    double minX = Double.parseDouble(parts[0]);
    double minY = Double.parseDouble(parts[1]);
    return new double[] {
      minX, minY, minX + Double.parseDouble(parts[2]), minY + Double.parseDouble(parts[3])
    };
  }
}
