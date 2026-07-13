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
 * Self-message (source lifeline == target lifeline) hook geometry render check. Tasks 1-2 of the
 * self-message fix gave the {@code m2} edge stem-anchored hook points (fixtures/layout-result/
 * uml-sequence-self-message.json: {@code (476,400) (516,400) (516,424) (476,424)} -- out from the
 * "service" lifeline stem, down, and back to the same stem). This test locks in that {@link
 * dev.dediren.plugins.render.node.uml.UmlSequenceRenderer} actually renders that hook: the emitted
 * {@code d=} path walks all four points with both the first and last x on the lifeline stem's
 * centre-x, and a {@code marker-end} arrowhead is attached so the arrow returns to the stem rather
 * than floating in space.
 */
class SequenceSelfMessageHookTest {

  private static final String LAYOUT = "fixtures/layout-result/uml-sequence-self-message.json";
  private static final String POLICY = "fixtures/render-policy/uml-svg.json";

  // Mirrors fixtures/render-metadata/uml-sequence-basic.json's shape for the self-message source
  // (fixtures/source/valid-uml-sequence-self-message.json): Interaction + two Lifelines, three
  // Messages, with m2 (the self-call) carrying message_sort "synchCall" so it resolves to a
  // filled-arrow marker-end, not "none".
  private static final String RENDER_METADATA =
      """
      {
        "render_metadata_schema_version": "render-metadata.schema.v1",
        "semantic_profile": "uml",
        "nodes": {
          "interaction-self-message": { "type": "Interaction", "source_id": "interaction-self-message" },
          "customer": {
            "type": "Lifeline",
            "source_id": "customer",
            "properties": { "interaction": "interaction-self-message" }
          },
          "service": {
            "type": "Lifeline",
            "source_id": "service",
            "properties": { "interaction": "interaction-self-message" }
          }
        },
        "edges": {
          "m1": {
            "type": "Message",
            "source_id": "m1",
            "properties": {
              "interaction": "interaction-self-message",
              "sequence": 1,
              "message_sort": "synchCall"
            }
          },
          "m2": {
            "type": "Message",
            "source_id": "m2",
            "properties": {
              "interaction": "interaction-self-message",
              "sequence": 2,
              "message_sort": "synchCall"
            }
          },
          "m3": {
            "type": "Message",
            "source_id": "m3",
            "properties": {
              "interaction": "interaction-self-message",
              "sequence": 3,
              "message_sort": "reply"
            }
          }
        },
        "groups": {}
      }
      """;

  @Test
  void selfMessageHookPathReturnsToTheStemWithAnArrowhead() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    input.set("layout_result", RenderTestSupport.fixtureJson(LAYOUT));
    input.set("render_metadata", JsonSupport.objectMapper().readTree(RENDER_METADATA));
    input.set("policy", RenderTestSupport.fixtureJson(POLICY));

    Document svg = SvgAudit.parse(RenderTestSupport.render(input));

    double stemX = lifelineStemX(svg, "service");
    assertThat(stemX).as("service lifeline stem centre-x").isEqualTo(476.0);

    Element path = pathWithAttribute(svg, "data-dediren-sequence-message", "m2");

    // The hook: stem -> stem+40 -> down -> back to the stem (fixtures/layout-result/
    // uml-sequence-self-message.json's m2 points, verbatim -- edgePath emits raw layout points
    // with no additional offset).
    assertThat(path.getAttribute("d"))
        .isEqualTo("M 476.0 400.0 L 516.0 400.0 L 516.0 424.0 L 476.0 424.0");

    List<double[]> points = pathPoints(path.getAttribute("d"));
    assertThat(points).hasSize(4);
    assertThat(points.get(0)[0])
        .as("hook's first point must start on the service lifeline stem")
        .isEqualTo(stemX);
    assertThat(points.get(points.size() - 1)[0])
        .as("hook's last point must return to the service lifeline stem")
        .isEqualTo(stemX);

    // The arrowhead: a marker-end is attached, and the referenced <marker> is oriented so its tip
    // (refX=9) lands on the endpoint -- here the stem, at the end of the westward return leg.
    String markerRef = path.getAttribute("marker-end");
    assertThat(markerRef).isEqualTo("url(#marker-end-m2)");

    Element marker = elementWithId(svg, "marker-end-m2");
    assertThat(marker.getAttribute("refX")).isEqualTo("9");
    assertThat(marker.getAttribute("orient")).isEqualTo("auto");
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

  private static Element elementWithId(Document svg, String id) {
    NodeList markers = svg.getElementsByTagName("marker");
    for (int index = 0; index < markers.getLength(); index++) {
      Element marker = (Element) markers.item(index);
      if (id.equals(marker.getAttribute("id"))) {
        return marker;
      }
    }
    throw new AssertionError("no <marker> with id=" + id);
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
