package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.node.ObjectNode;

class SequenceLifelineLabelTest {

  private static final String LONG_PARTICIPANT =
      "editor-apps /campaign-editor + Shared MapAuthoringWorkspace";

  @Test
  void longParticipantNameWrapsInsideTheLifelineHead() throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(
            "fixtures/layout-result/uml-sequence-basic.json",
            "fixtures/render-policy/uml-svg.json",
            "fixtures/render-metadata/uml-sequence-basic.json");
    ((ObjectNode) input.at("/layout_result/nodes/1"))
        .put("height", 64.0)
        .put("label", LONG_PARTICIPANT);

    Document document = SvgAudit.parse(RenderTestSupport.render(input));
    Element participant = groupForNode(document, "customer");
    Element label = (Element) participant.getElementsByTagName("text").item(0);
    Element head = (Element) participant.getElementsByTagName("rect").item(0);
    NodeList lines = label.getElementsByTagName("tspan");

    assertThat(lines.getLength()).as("long participant name should wrap").isGreaterThan(1);
    assertThat(label.getAttribute("text-anchor")).isEqualTo("middle");
    double headLeft = Double.parseDouble(head.getAttribute("x"));
    double headRight = headLeft + Double.parseDouble(head.getAttribute("width"));
    double horizontalPadding = 10.0;
    double svgRoundingTolerance = 0.2;
    List<String> renderedLines = new ArrayList<>();
    for (int index = 0; index < lines.getLength(); index++) {
      Element line = (Element) lines.item(index);
      renderedLines.add(line.getTextContent());
      double lineCenter = Double.parseDouble(line.getAttribute("x"));
      double lineLength = Double.parseDouble(line.getAttribute("textLength"));
      assertThat(lineLength)
          .as("wrapped line %s stays inside the 140 px head with horizontal padding", index)
          .isLessThanOrEqualTo(120.0);
      assertThat(lineCenter - lineLength / 2.0)
          .as("wrapped line %s starts inside the head padding", index)
          .isGreaterThanOrEqualTo(headLeft + horizontalPadding - svgRoundingTolerance);
      assertThat(lineCenter + lineLength / 2.0)
          .as("wrapped line %s ends inside the head padding", index)
          .isLessThanOrEqualTo(headRight - horizontalPadding + svgRoundingTolerance);
    }
    assertThat(String.join("", renderedLines).replaceAll("\\s+", ""))
        .isEqualTo(LONG_PARTICIPANT.replaceAll("\\s+", ""));

    double fontSize = Double.parseDouble(label.getAttribute("font-size"));
    double firstBaseline = Double.parseDouble(label.getAttribute("y"));
    double lastBaseline = firstBaseline;
    for (int index = 1; index < lines.getLength(); index++) {
      lastBaseline += Double.parseDouble(((Element) lines.item(index)).getAttribute("dy"));
    }
    double headTop = Double.parseDouble(head.getAttribute("y"));
    double headBottom = headTop + Double.parseDouble(head.getAttribute("height"));
    assertThat(firstBaseline - fontSize).isGreaterThanOrEqualTo(headTop - svgRoundingTolerance);
    assertThat(lastBaseline + fontSize * 0.25)
        .isLessThanOrEqualTo(headBottom + svgRoundingTolerance);
  }

  private static Element groupForNode(Document document, String nodeId) {
    NodeList groups = document.getElementsByTagName("g");
    for (int index = 0; index < groups.getLength(); index++) {
      Element group = (Element) groups.item(index);
      if (nodeId.equals(group.getAttribute("data-dediren-node-id"))) {
        return group;
      }
    }
    throw new AssertionError("no sequence participant group for " + nodeId);
  }
}
