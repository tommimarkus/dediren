package dev.dediren.plugins.render;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

/**
 * Generic (decorator-less) nodes can pick a shape from the render policy. Without a {@code shape}
 * the node stays the historical rounded rectangle; with a {@code shape} it renders that shape's SVG
 * element carrying {@code data-dediren-node-shape="<name>"}. Shapes are the widest lever for
 * generic-graph styling, where no notation fixes the geometry.
 */
class GenericNodeShapeTest {

  private static final List<String> SHAPES =
      List.of(
          "rectangle",
          "rounded_rectangle",
          "ellipse",
          "circle",
          "diamond",
          "hexagon",
          "parallelogram",
          "stadium",
          "cylinder",
          "triangle");

  @Test
  void genericNodeRendersEachSelectedShape() throws Exception {
    for (String shape : SHAPES) {
      String svg = renderWithNodeShape(shape);
      assertThat(svg)
          .describedAs("shape %s", shape)
          .contains("data-dediren-node-shape=\"" + shape + "\"");
      // Each hand-authored shape (polygon points, cylinder path) must be well-formed SVG.
      SvgAudit.auditStructure(svg);
    }
  }

  @Test
  void ellipseShapeEmitsAnEllipseElement() throws Exception {
    assertThat(renderWithNodeShape("ellipse")).contains("<ellipse");
  }

  @Test
  void genericShapesFixtureRendersEllipseAndHexagon() throws Exception {
    String svg =
        RenderTestSupport.renderFixtures(
            "fixtures/layout-result/basic.json",
            "fixtures/render-policy/generic-shapes-svg.json",
            null);

    assertThat(svg).contains("data-dediren-node-shape=\"ellipse\"");
    assertThat(svg).contains("data-dediren-node-shape=\"hexagon\"");
  }

  @Test
  void withoutShapeGenericNodeStaysARect() throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(
            "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json", null);

    String svg = RenderTestSupport.render(input);

    assertThat(svg).doesNotContain("data-dediren-node-shape=\"ellipse\"");
    assertThat(svg).contains("<rect");
  }

  private static String renderWithNodeShape(String shape) throws Exception {
    ObjectNode input =
        RenderTestSupport.fixtureInput(
            "fixtures/layout-result/basic.json", "fixtures/render-policy/default-svg.json", null);
    ObjectNode policy = (ObjectNode) input.get("policy");
    policy.putObject("style").putObject("node_overrides").putObject("client").put("shape", shape);
    return RenderTestSupport.render(input);
  }
}
