package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Typography is a free presentation dimension. The global font gains weight and italic style; node
 * and group labels gain per-element {@code font_weight}, {@code font_style}, {@code font_family},
 * {@code label_align}, and {@code label_opacity}; edge labels gain {@code label_opacity}. The
 * deterministic {@code textLength} width pin is unaffected — {@code SvgAudit} always measures with
 * the bundled regular font — so bold/italic/family labels stay hermetic.
 */
class TypographyTest {

  @Test
  void globalFontWeightAndStyleReachTheRootGroup() throws Exception {
    ObjectNode input = defaultInput();
    ObjectNode font = ((ObjectNode) input.get("policy")).putObject("style").putObject("font");
    font.put("weight", "bold").put("style", "italic");

    String svg = RenderTestSupport.render(input);

    assertThat(svg).contains("font-weight=\"bold\"").contains("font-style=\"italic\"");
  }

  @Test
  void nodeLabelTypographyIsEmitted() throws Exception {
    ObjectNode input = defaultInput();
    ObjectNode client =
        ((ObjectNode) input.get("policy"))
            .putObject("style")
            .putObject("node_overrides")
            .putObject("client");
    client
        .put("font_weight", "bold")
        .put("font_style", "italic")
        .put("font_family", "Georgia")
        .put("label_opacity", 0.7);

    String svg = RenderTestSupport.render(input);

    assertThat(svg)
        .contains("font-family=\"Georgia\"")
        .contains("font-weight=\"bold\"")
        .contains("font-style=\"italic\"")
        .contains("fill-opacity=\"0.7\"");
  }

  @Test
  void nodeLabelAlignStartSetsTextAnchor() throws Exception {
    ObjectNode input = defaultInput();
    ((ObjectNode) input.get("policy"))
        .putObject("style")
        .putObject("node_overrides")
        .putObject("client")
        .put("label_align", "start");

    String svg = RenderTestSupport.render(input);

    assertThat(svg).contains("text-anchor=\"start\"");
  }

  @Test
  void edgeLabelOpacityIsEmitted() throws Exception {
    ObjectNode input = defaultInput();
    ((ObjectNode) input.get("policy"))
        .putObject("style")
        .putObject("edge_overrides")
        .putObject("client-calls-api")
        .put("label_opacity", 0.5);

    String svg = RenderTestSupport.render(input);

    assertThat(svg).contains("fill-opacity=\"0.5\"");
  }

  private static ObjectNode defaultInput() throws Exception {
    return RenderTestSupport.fixtureInput(
        "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json", null);
  }
}
