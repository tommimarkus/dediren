package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;

/**
 * Appearance-layer audit: paint order (nodes cover edges, never the reverse) and node-label
 * contrast (labels readable against their own fill, in light and dark themes). Scoped to the
 * standard renderer; the UML sequence renderer has its own element structure.
 */
class SvgAppearanceAuditTest {

  @Test
  void detectsNodeGroupPaintedBeforeEdgeGroup() {
    Document document =
        SvgAudit.parse(
            "<svg viewBox=\"0 0 10 10\"><g font-family=\"x\">"
                + "<g data-dediren-node-id=\"n\"/><g data-dediren-edge-id=\"e\"/></g></svg>");
    assertThatThrownBy(() -> SvgAudit.assertPaintOrderGroupsEdgesNodes(document))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("paint order");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dev.dediren.plugins.render.RenderScenarios#standard")
  void paintOrderIsGroupsThenEdgesThenNodes(
      String name, String layout, String policy, String metadata) throws Exception {
    SvgAudit.assertPaintOrderGroupsEdgesNodes(
        SvgAudit.parse(RenderTestSupport.renderFixtures(layout, policy, metadata)));
  }

  @Test
  void detectsLowContrastNodeLabel() {
    // Light label (#eeeeee) on a light fill (#ffffff): ratio ≈ 1.1, well under the floor.
    Document document =
        SvgAudit.parse(
            "<svg viewBox=\"0 0 10 10\"><g data-dediren-node-id=\"n\">"
                + "<rect data-dediren-node-shape=\"r\" fill=\"#ffffff\"/>"
                + "<text fill=\"#eeeeee\">x</text></g></svg>");
    assertThatThrownBy(() -> SvgAudit.assertNodeLabelContrast(document, 3.0))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("contrast");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("dev.dediren.plugins.render.RenderScenarios#standard")
  void nodeLabelsMeetContrastFloor(String name, String layout, String policy, String metadata)
      throws Exception {
    SvgAudit.assertNodeLabelContrast(
        SvgAudit.parse(RenderTestSupport.renderFixtures(layout, policy, metadata)), 3.0);
  }

  @Test
  void darkThemeNodeLabelsAreReadableAndStructurallySound() throws Exception {
    String svg =
        RenderTestSupport.renderFixtures(
            "fixtures/layout-result/pipeline-rich.json",
            "fixtures/render-policy/dark-svg.json",
            null);

    SvgAudit.auditStructure(svg);
    SvgAudit.assertNodeLabelContrast(SvgAudit.parse(svg), 3.0);
  }
}
