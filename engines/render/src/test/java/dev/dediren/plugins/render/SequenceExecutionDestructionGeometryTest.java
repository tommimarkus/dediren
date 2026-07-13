package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.json.JsonSupport;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.node.ObjectNode;

/**
 * Locks in that the execution-specification activation bar and the destruction-occurrence marker
 * (added by Task 6's layout fix to {@code fixtures/layout-result/uml-sequence-lifecycle.json}) are
 * actually DRAWN in the right place: {@link
 * dev.dediren.plugins.render.node.uml.UmlSequenceRenderer} already paints executions and
 * destructions straight from {@code node.x/y/width/height} ({@link
 * dev.dediren.plugins.render.node.uml.UmlSequenceModel} routes {@code ExecutionSpecification} to
 * {@code executions} and {@code DestructionOccurrenceSpecification} to {@code destructions}); this
 * test asserts against the real emitted SVG, not the layout-result JSON, so it fails if a future
 * change decouples the two.
 */
class SequenceExecutionDestructionGeometryTest {

  private static final String LAYOUT = "fixtures/layout-result/uml-sequence-lifecycle.json";
  private static final String POLICY = "fixtures/render-policy/uml-svg.json";

  // Mirrors fixtures/source/valid-uml-sequence-lifecycle.json (Task 6): Interaction + three
  // Lifelines, an ExecutionSpecification covering "service", a DestructionOccurrenceSpecification
  // covering "worker", and four Messages -- m3 (destroyWorker) is the deleteMessage that must reach
  // the worker's destruction marker. No render-metadata fixture exists for this layout fixture yet
  // (same situation SequenceSelfMessageHookTest was in for uml-sequence-self-message.json), so this
  private static final String METADATA = "fixtures/render-metadata/uml-sequence-lifecycle.json";

  @Test
  void executionBarIsCentredOnItsLifelineStem() throws Exception {
    Document svg = render();

    double stemX = lifelineStemX(svg, "service");
    Element bar = rectWithShape(svg, "uml_execution_specification");
    double barCentreX =
        Double.parseDouble(bar.getAttribute("x"))
            + Double.parseDouble(bar.getAttribute("width")) / 2.0;

    assertThat(barCentreX)
        .as("exec-service activation bar must be centred on the service lifeline stem")
        .isEqualTo(stemX);
  }

  @Test
  void destructionMarkerIsCentredOnItsLifelineStem() throws Exception {
    Document svg = render();

    double stemX = lifelineStemX(svg, "worker");
    List<Element> markerLines = deleteMarkerLines(svg, "worker-destroyed");
    assertThat(markerLines).hasSize(2);

    for (Element line : markerLines) {
      double x1 = Double.parseDouble(line.getAttribute("x1"));
      double x2 = Double.parseDouble(line.getAttribute("x2"));
      assertThat((x1 + x2) / 2.0)
          .as("each stroke of the worker-destroyed X must straddle the worker lifeline stem")
          .isEqualTo(stemX);
    }
  }

  @Test
  void deleteMessagePathReachesTheDestructionMarker() throws Exception {
    Document svg = render();

    Element path = pathWithAttribute(svg, "data-dediren-sequence-message", "m3");
    List<double[]> points = pathPoints(path.getAttribute("d"));
    double[] end = points.get(points.size() - 1);

    List<Element> markerLines = deleteMarkerLines(svg, "worker-destroyed");
    double markerMinX = Double.MAX_VALUE;
    double markerMaxX = -Double.MAX_VALUE;
    double markerMinY = Double.MAX_VALUE;
    double markerMaxY = -Double.MAX_VALUE;
    for (Element line : markerLines) {
      for (String xAttr : List.of("x1", "x2")) {
        markerMinX = Math.min(markerMinX, Double.parseDouble(line.getAttribute(xAttr)));
        markerMaxX = Math.max(markerMaxX, Double.parseDouble(line.getAttribute(xAttr)));
      }
      for (String yAttr : List.of("y1", "y2")) {
        markerMinY = Math.min(markerMinY, Double.parseDouble(line.getAttribute(yAttr)));
        markerMaxY = Math.max(markerMaxY, Double.parseDouble(line.getAttribute(yAttr)));
      }
    }

    assertThat(end[0])
        .as(
            "the destroyWorker message must terminate exactly on the marker's near edge, not"
                + " short of it")
        .isEqualTo(markerMinX);
    assertThat(end[1])
        .as("the destroyWorker message endpoint must sit within the marker's vertical extent")
        .isBetween(markerMinY, markerMaxY);
  }

  private static Document render() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", RenderTestSupport.fixtureJson(LAYOUT));
    input.set("render_metadata", RenderTestSupport.fixtureJson(METADATA));
    input.set("policy", RenderTestSupport.fixtureJson(POLICY));
    return SvgAudit.parse(RenderTestSupport.render(input));
  }

  private static double lifelineStemX(Document svg, String lifelineId) {
    NodeList lines = svg.getElementsByTagName("line");
    for (int index = 0; index < lines.getLength(); index++) {
      Element line = (Element) lines.item(index);
      if (lifelineId.equals(line.getAttribute("data-dediren-sequence-lifeline-stem"))) {
        return Double.parseDouble(line.getAttribute("x1"));
      }
    }
    throw new AssertionError("no lifeline stem for " + lifelineId);
  }

  private static Element rectWithShape(Document svg, String shape) {
    NodeList rects = svg.getElementsByTagName("rect");
    for (int index = 0; index < rects.getLength(); index++) {
      Element rect = (Element) rects.item(index);
      if (shape.equals(rect.getAttribute("data-dediren-node-shape"))) {
        return rect;
      }
    }
    throw new AssertionError("no <rect> with data-dediren-node-shape=" + shape);
  }

  private static List<Element> deleteMarkerLines(Document svg, String markerId) {
    NodeList groups = svg.getElementsByTagName("g");
    for (int index = 0; index < groups.getLength(); index++) {
      Element group = (Element) groups.item(index);
      if (markerId.equals(group.getAttribute("data-dediren-sequence-delete-marker"))) {
        List<Element> lines = new ArrayList<>();
        NodeList children = group.getElementsByTagName("line");
        for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
          lines.add((Element) children.item(childIndex));
        }
        return lines;
      }
    }
    throw new AssertionError("no delete marker group for " + markerId);
  }

  private static Element pathWithAttribute(Document svg, String attribute, String value) {
    NodeList paths = svg.getElementsByTagName("path");
    for (int index = 0; index < paths.getLength(); index++) {
      Element path = (Element) paths.item(index);
      if (value.equals(path.getAttribute(attribute))) {
        return path;
      }
    }
    throw new AssertionError("no <path> with " + attribute + "=" + value);
  }

  /** Parses an SVG path {@code d=} string of the form {@code "M x y L x y L x y ..."}. */
  private static List<double[]> pathPoints(String d) {
    String[] tokens = d.trim().split("\\s+");
    List<double[]> points = new ArrayList<>();
    for (int index = 0; index < tokens.length; ) {
      String command = tokens[index];
      if ("M".equals(command) || "L".equals(command)) {
        double x = Double.parseDouble(tokens[index + 1]);
        double y = Double.parseDouble(tokens[index + 2]);
        points.add(new double[] {x, y});
        index += 3;
      } else {
        index++;
      }
    }
    return points;
  }
}
