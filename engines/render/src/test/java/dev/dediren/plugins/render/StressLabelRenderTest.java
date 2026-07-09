package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import dev.dediren.contracts.json.JsonSupport;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Adversarial label rendering: CJK, emoji, a very long unbroken identifier, mixed scripts, and an
 * empty label. The headline case is CJK — the renderer used to pin every non-ASCII glyph at 0.6 em,
 * so a CJK label's {@code textLength} was ~40% too narrow and {@code lengthAdjust="spacing"}
 * visibly squeezed it. This drives the fix end to end: the emitted pin must reflect the full em
 * square.
 */
class StressLabelRenderTest {

  private static final String CJK = "注文管理サービス";

  @Test
  void cjkLabelIsPinnedAtFullEmWidthEndToEnd() throws Exception {
    Document document = SvgAudit.parse(RenderTestSupport.render(stressInput()));
    Element text = firstNodeText(document, "cjk");

    assertThat(text.getTextContent()).isEqualTo(CJK);
    double textLength = Double.parseDouble(text.getAttribute("textLength"));
    double fontSize = Double.parseDouble(text.getAttribute("font-size"));
    int glyphs = CJK.codePointCount(0, CJK.length());

    assertThat(textLength / (glyphs * fontSize))
        .as("each CJK glyph is pinned at ~1 em, not the retired 0.6 em non-ASCII fallback")
        .isCloseTo(1.0, within(0.05));
  }

  @Test
  void adversarialLabelsRenderStructurallySound() throws Exception {
    SvgAudit.auditStructure(RenderTestSupport.render(stressInput()));
  }

  private static ObjectNode stressInput() throws Exception {
    ObjectNode input = JsonSupport.objectMapper().createObjectNode();
    ObjectNode layout = input.putObject("layout_result");
    layout.put("layout_result_schema_version", "layout-result.schema.v2");
    layout.put("view_id", "stress-labels");

    ArrayNode nodes = layout.putArray("nodes");
    // Generously sized so CJK/emoji fit at full font size (no shrink), isolating the width metric.
    addNode(nodes, "cjk", 40, 40, CJK);
    addNode(nodes, "emoji", 40, 160, "🚀🚀🚀");
    addNode(nodes, "long", 40, 280, "x".repeat(120));
    addNode(nodes, "mixed", 40, 400, "注文Order管理Line");
    addNode(nodes, "empty", 40, 520, "");

    layout.putArray("edges");
    layout.putArray("groups");
    layout.putArray("warnings");
    input.set("policy", RenderTestSupport.fixtureJson("fixtures/render-policy/default-svg.json"));
    return input;
  }

  private static void addNode(ArrayNode nodes, String id, double x, double y, String label) {
    ObjectNode node = nodes.addObject();
    node.put("id", id).put("source_id", id).put("projection_id", id);
    node.put("x", x).put("y", y).put("width", 300).put("height", 90).put("label", label);
  }

  private static Element firstNodeText(Document document, String nodeId) {
    NodeList groups = document.getElementsByTagName("g");
    for (int index = 0; index < groups.getLength(); index++) {
      Element group = (Element) groups.item(index);
      if (nodeId.equals(group.getAttribute("data-dediren-node-id"))) {
        NodeList texts = group.getElementsByTagName("text");
        if (texts.getLength() > 0) {
          return (Element) texts.item(0);
        }
      }
    }
    throw new AssertionError("no <text> found in node group " + nodeId);
  }
}
