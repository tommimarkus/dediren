package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * UML sequence combined-fragment alignment: a fragment frame is render-synthesized (it is not a
 * layout node), so its geometry must be checked against reality. Two invariants a mis-computed
 * frame would violate — the frame must horizontally span every lifeline it declares it covers, and
 * it must vertically contain every message its operands enclose. MainTest already checks the
 * fragment's markup is emitted; nothing checked the geometry lines up with the lifelines and
 * messages.
 */
class SequenceFragmentAlignmentTest {

  private static final String LAYOUT = "fixtures/layout-result/uml-sequence-fragments.json";
  private static final String POLICY = "fixtures/render-policy/uml-svg.json";
  private static final String METADATA = "fixtures/render-metadata/uml-sequence-fragments.json";
  private static final double TOLERANCE = 1.0;

  // fixtures/layout-result/uml-sequence-fragments.json and
  // fixtures/layout-result/uml-sequence-fragment-chrome.json are real generic-graph + elk-layout
  // engine output from the same (source, view) pair (Plan B P2, Task 10/11 fixture regeneration)
  // and are therefore byte-identical; both files are kept because other suites still reference
  // each by name (see LayoutFixtureRegenerator's Javadoc), but this test only needs one, so all
  // three cases below share LAYOUT, including the chrome/label-clearance check that previously
  // needed a separately-spaced real fixture while LAYOUT was still hand-authored/idealised.
  private static final String CHROME_LAYOUT = LAYOUT;
  // Message labels are one font-size tall; chrome (operator tab, operand separators, guards) must
  // stay at least this far from every message label baseline to avoid overlapping the text.
  private static final double MESSAGE_LABEL_CLEARANCE = 14.0;

  @Test
  void everyCombinedFragmentFrameSpansTheLifelinesItCovers() throws Exception {
    Document svg = SvgAudit.parse(RenderTestSupport.renderFixtures(LAYOUT, POLICY, METADATA));
    JsonNode metadata = RenderTestSupport.fixtureJson(METADATA);
    Map<String, Double> axisX = lifelineAxisXById(svg);

    int fragmentsChecked = 0;
    for (Map.Entry<String, JsonNode> entry : nodes(metadata)) {
      JsonNode node = entry.getValue();
      if (!"CombinedFragment".equals(node.at("/type").asText())) {
        continue;
      }
      String fragmentId = entry.getKey();
      double[] frame = fragmentBox(svg, fragmentId);
      for (JsonNode covered : node.at("/properties/covered")) {
        String lifeline = covered.asText();
        assertThat(axisX).as("lifeline stem for %s", lifeline).containsKey(lifeline);
        assertThat(axisX.get(lifeline))
            .as(
                "fragment %s frame [x %.1f..%.1f] must span covered lifeline %s",
                fragmentId, frame[0], frame[2], lifeline)
            .isBetween(frame[0] - TOLERANCE, frame[2] + TOLERANCE);
      }
      fragmentsChecked++;
    }
    assertThat(fragmentsChecked)
        .as("the fragments fixture must exercise combined fragments")
        .isGreaterThan(0);
  }

  @Test
  void everyCombinedFragmentFrameContainsItsMessages() throws Exception {
    Document svg = SvgAudit.parse(RenderTestSupport.renderFixtures(LAYOUT, POLICY, METADATA));
    JsonNode metadata = RenderTestSupport.fixtureJson(METADATA);
    Map<String, Double> messageY = messageYById(RenderTestSupport.fixtureJson(LAYOUT));

    for (Map.Entry<String, JsonNode> entry : nodes(metadata)) {
      JsonNode node = entry.getValue();
      if (!"CombinedFragment".equals(node.at("/type").asText())) {
        continue;
      }
      String fragmentId = entry.getKey();
      double[] frame = fragmentBox(svg, fragmentId);
      for (JsonNode operandRef : node.at("/properties/operands")) {
        JsonNode operand = metadata.at("/nodes/" + operandRef.asText());
        for (JsonNode fragmentRef : operand.at("/properties/fragments")) {
          Double y = messageY.get(fragmentRef.asText());
          if (y == null) {
            continue; // an operand fragment that is not a message edge
          }
          assertThat(y)
              .as(
                  "fragment %s [y %.1f..%.1f] must contain message %s",
                  fragmentId, frame[1], frame[3], fragmentRef.asText())
              .isBetween(frame[1] - TOLERANCE, frame[3] + TOLERANCE);
        }
      }
    }
  }

  @Test
  void combinedFragmentChromeClearsMessageLabels() throws Exception {
    Document svg =
        SvgAudit.parse(RenderTestSupport.renderFixtures(CHROME_LAYOUT, POLICY, METADATA));

    List<Double> messageLabelYs =
        elementYs(svg, "text", "y", "data-dediren-sequence-message-label");
    assertThat(messageLabelYs).as("chrome fixture must render message labels").isNotEmpty();

    List<Double> chromeYs = new ArrayList<>();
    chromeYs.addAll(elementYs(svg, "line", "y1", "data-dediren-sequence-operand-separator"));
    chromeYs.addAll(elementYs(svg, "text", "y", "data-dediren-sequence-operand-guard"));
    chromeYs.addAll(elementYs(svg, "text", "y", "data-dediren-sequence-fragment-operator"));
    assertThat(chromeYs).as("chrome fixture must render fragment chrome").isNotEmpty();

    for (double chromeY : chromeYs) {
      double nearest =
          messageLabelYs.stream()
              .mapToDouble(y -> Math.abs(y - chromeY))
              .min()
              .orElse(Double.MAX_VALUE);
      assertThat(nearest)
          .as("fragment chrome at y=%.1f must clear every message label", chromeY)
          .isGreaterThanOrEqualTo(MESSAGE_LABEL_CLEARANCE);
    }
  }

  private static List<Double> elementYs(
      Document svg, String tag, String yAttribute, String markerAttribute) {
    List<Double> ys = new ArrayList<>();
    NodeList elements = svg.getElementsByTagName(tag);
    for (int index = 0; index < elements.getLength(); index++) {
      Element element = (Element) elements.item(index);
      if (element.hasAttribute(markerAttribute)) {
        ys.add(Double.parseDouble(element.getAttribute(yAttribute)));
      }
    }
    return ys;
  }

  private static Iterable<Map.Entry<String, JsonNode>> nodes(JsonNode metadata) {
    return ((ObjectNode) metadata.get("nodes")).properties();
  }

  private static Map<String, Double> lifelineAxisXById(Document svg) {
    Map<String, Double> axisX = new HashMap<>();
    NodeList lines = svg.getElementsByTagName("line");
    for (int index = 0; index < lines.getLength(); index++) {
      Element line = (Element) lines.item(index);
      String lifeline = line.getAttribute("data-dediren-sequence-lifeline-stem");
      if (!lifeline.isEmpty()) {
        axisX.put(lifeline, Double.parseDouble(line.getAttribute("x1")));
      }
    }
    return axisX;
  }

  private static Map<String, Double> messageYById(JsonNode layoutResult) {
    Map<String, Double> messageY = new HashMap<>();
    for (JsonNode edge : layoutResult.at("/edges")) {
      JsonNode points = edge.at("/points");
      if (!points.isEmpty()) {
        messageY.put(edge.at("/id").asText(), points.get(0).at("/y").asDouble());
      }
    }
    return messageY;
  }

  /** {minX, minY, maxX, maxY} of the combined-fragment frame rect. */
  private static double[] fragmentBox(Document svg, String fragmentId) {
    Element group = groupWithAttribute(svg, "data-dediren-sequence-combined-fragment", fragmentId);
    NodeList rects = group.getElementsByTagName("rect");
    for (int index = 0; index < rects.getLength(); index++) {
      Element rect = (Element) rects.item(index);
      if ("uml_combined_fragment".equals(rect.getAttribute("data-dediren-node-shape"))) {
        double x = Double.parseDouble(rect.getAttribute("x"));
        double y = Double.parseDouble(rect.getAttribute("y"));
        return new double[] {
          x,
          y,
          x + Double.parseDouble(rect.getAttribute("width")),
          y + Double.parseDouble(rect.getAttribute("height"))
        };
      }
    }
    throw new AssertionError("no uml_combined_fragment rect in fragment " + fragmentId);
  }

  private static Element groupWithAttribute(Document svg, String attribute, String value) {
    NodeList groups = svg.getElementsByTagName("g");
    for (int index = 0; index < groups.getLength(); index++) {
      Element group = (Element) groups.item(index);
      if (value.equals(group.getAttribute(attribute))) {
        return group;
      }
    }
    throw new AssertionError("no <g> with " + attribute + "=" + value);
  }
}
