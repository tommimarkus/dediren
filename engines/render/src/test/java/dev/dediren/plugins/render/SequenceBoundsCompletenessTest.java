package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The sequence lane's counterpart to {@link DocumentBoundsCompletenessTest}.
 *
 * <p>The sequence lane has its own {@code PlacedElement} kinds, so the generic lane's
 * bounds-completeness test says nothing about it. {@code PlacedGate} in particular was reachable by
 * no test at all: {@code "Gate"} appears in the fixture corpus only as a style-override key in
 * {@code fixtures/render-policy/uml-svg.json}, never as an actual node, so its radius-floor
 * arithmetic never executed.
 *
 * <p>That floor is the same shape as the ArchiMate junction defect: {@code max(4.0, min(w, h) / 2)}
 * can exceed the node's own half-extent, so the circle overhangs the box that used to be the only
 * thing measured. A gate small enough to trigger it, at zero margin, is the case that catches it.
 */
class SequenceBoundsCompletenessTest {

  // 6x6 makes the floor bite: max(4.0, 3.0) = 4.0 against a 3.0 half-extent, so the circle
  // overhangs its node box by 1.0 plus half the stroke on every side.
  private static final double GATE_SIZE = 6.0;
  private static final double GATE_X = 559.0;
  private static final double GATE_Y = 330.0;

  @Test
  void aGateCircleWiderThanItsNodeBoxStaysInsideTheViewBox() throws Exception {
    Document svg = SvgAudit.parse(RenderTestSupport.render(sequenceWithGate()));

    Element circle = firstGateCircle(svg);
    double cx = Double.parseDouble(circle.getAttribute("cx"));
    double cy = Double.parseDouble(circle.getAttribute("cy"));
    double r = Double.parseDouble(circle.getAttribute("r"));
    double strokeHalf = Double.parseDouble(circle.getAttribute("stroke-width")) / 2.0;

    // Anti-vacuity: the case only means something if the drawn circle really does overhang the
    // node box the old bounds pass measured.
    assertThat(r)
        .as("gate radius must exceed the node half-extent or this fixture proves nothing")
        .isGreaterThan(GATE_SIZE / 2.0);

    double[] viewBox = viewBox(svg);
    double reach = r + strokeHalf;
    // %.1f on the viewBox can round up to 0.05 inward; the register records that separately.
    double tolerance = 0.05;
    assertThat(cx - reach).isGreaterThanOrEqualTo(viewBox[0] - tolerance);
    assertThat(cy - reach).isGreaterThanOrEqualTo(viewBox[1] - tolerance);
    assertThat(cx + reach).isLessThanOrEqualTo(viewBox[0] + viewBox[2] + tolerance);
    assertThat(cy + reach).isLessThanOrEqualTo(viewBox[1] + viewBox[3] + tolerance);
  }

  private static Element firstGateCircle(Document svg) {
    var circles = svg.getElementsByTagName("circle");
    for (int index = 0; index < circles.getLength(); index++) {
      Element circle = (Element) circles.item(index);
      if ("uml_gate".equals(circle.getAttribute("data-dediren-node-shape"))) {
        return circle;
      }
    }
    throw new AssertionError("no gate circle was emitted — the fixture never reached PlacedGate");
  }

  private static double[] viewBox(Document svg) {
    String[] parts = svg.getDocumentElement().getAttribute("viewBox").trim().split("\\s+");
    return new double[] {
      Double.parseDouble(parts[0]),
      Double.parseDouble(parts[1]),
      Double.parseDouble(parts[2]),
      Double.parseDouble(parts[3])
    };
  }

  /** The basic sequence fixture with a gate added at the frame's right edge, and no margin. */
  private static ObjectNode sequenceWithGate() throws Exception {
    ObjectNode layout =
        (ObjectNode)
            RenderTestSupport.fixtureJson("fixtures/layout-result/uml-sequence-basic.json");
    ArrayNode nodes = layout.withArray("nodes");
    ObjectNode gate = nodes.addObject();
    gate.put("id", "gate-out").put("source_id", "gate-out").put("projection_id", "gate-out");
    gate.put("x", GATE_X).put("y", GATE_Y).put("width", GATE_SIZE).put("height", GATE_SIZE);
    gate.put("label", "");

    ObjectNode metadata =
        (ObjectNode)
            RenderTestSupport.fixtureJson("fixtures/render-metadata/uml-sequence-basic.json");
    ObjectNode metaNodes = (ObjectNode) metadata.get("nodes");
    ObjectNode gateSelector = metaNodes.putObject("gate-out");
    gateSelector.put("type", "Gate").put("source_id", "gate-out");
    gateSelector.putObject("properties").put("interaction", "interaction-place-order");

    ObjectNode policy =
        (ObjectNode) RenderTestSupport.fixtureJson("fixtures/render-policy/uml-svg.json");
    ObjectNode margin = policy.putObject("margin");
    margin.put("top", 0).put("right", 0).put("bottom", 0).put("left", 0);

    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", layout);
    input.set("render_metadata", metadata);
    input.set("policy", policy);
    return input;
  }
}
