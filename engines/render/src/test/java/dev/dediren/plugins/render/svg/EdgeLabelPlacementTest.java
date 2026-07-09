package dev.dediren.plugins.render.svg;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.render.SvgEdgeLabelHorizontalPosition;
import dev.dediren.contracts.render.SvgEdgeLabelHorizontalSide;
import dev.dediren.contracts.render.SvgEdgeLabelPresentation;
import dev.dediren.contracts.render.SvgEdgeLabelVerticalPosition;
import dev.dediren.contracts.render.SvgEdgeLabelVerticalSide;
import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import dev.dediren.plugins.render.style.ResolvedEdgeStyle;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Placement invariants for {@link EdgeRenderer#edgeLabel}: a routed edge's label must stay attached
 * to its own route. The regression under test is the grouped internal fan-out ({@code
 * groupedInternalFanOutUsesRightwardServiceFlow}), where the overlap-dodging offset search
 * escalated so far it flung the "requests payment" label ~94px above its edge, into the empty
 * canvas over the group title — visually dissociated from the relationship it names.
 *
 * <p>The geometry below is the real ELK-laid-out result for that fixture (node rectangles, group
 * box, and routed edge points read back from the rendered SVG). The test replays the exact per-edge
 * placement loop {@link Geometry#svgBounds} runs, so it exercises production placement with
 * production obstacle boxes.
 */
class EdgeLabelPlacementTest {

  // The fixture renders node labels at font-size 14; edge labels scale to edgeLabelFontSize(14).
  private static final double BASE_FONT_SIZE = 14.0;

  // A label anchored this far outside its own route's bounding box is no longer beside the edge it
  // names. Side offsets are at most ~62px (vertical-run candidates); 40px of extra slack keeps
  // legitimate beside-the-route placements while excluding the ~94px detachment of the bug.
  private static final double ATTACHED_MARGIN = 40.0;

  private static final ResolvedEdgeStyle DEFAULT_STYLE =
      new ResolvedEdgeStyle(
          "#64748b",
          1.5,
          "#374151",
          SvgEdgeLineStyle.SOLID,
          SvgEdgeMarkerEnd.NONE,
          SvgEdgeMarkerEnd.FILLED_ARROW,
          SvgEdgeLabelHorizontalPosition.NEAR_START,
          SvgEdgeLabelHorizontalSide.AUTO,
          SvgEdgeLabelVerticalPosition.CENTER,
          SvgEdgeLabelVerticalSide.LEFT,
          SvgEdgeLabelPresentation.OUTLINE);

  @Test
  void fanOutEdgeLabelsStayAttachedToTheirRoutes() {
    LayoutResult result = groupedInternalFanOut();
    double fontSize = EdgeRenderer.edgeLabelFontSize(BASE_FONT_SIZE);

    List<LabelBox> placed = new ArrayList<>();
    for (int index = 0; index < result.edges().size(); index++) {
      LaidOutEdge edge = result.edges().get(index);
      EdgeLabel label =
          EdgeRenderer.edgeLabel(
              edge,
              DEFAULT_STYLE,
              Geometry.labelObstacleBoxesForEdge(result, index, placed),
              fontSize);
      placed.add(EdgeRenderer.edgeLabelVisibleBox(label, DEFAULT_STYLE.labelPresentation()));

      double minX = edge.points().stream().mapToDouble(Point::x).min().orElseThrow();
      double maxX = edge.points().stream().mapToDouble(Point::x).max().orElseThrow();
      double minY = edge.points().stream().mapToDouble(Point::y).min().orElseThrow();
      double maxY = edge.points().stream().mapToDouble(Point::y).max().orElseThrow();

      assertThat(label.y())
          .as("edge %s label y must stay beside its route [%.1f, %.1f]", edge.id(), minY, maxY)
          .isBetween(minY - ATTACHED_MARGIN, maxY + ATTACHED_MARGIN);
      assertThat(label.x())
          .as("edge %s label x must stay beside its route [%.1f, %.1f]", edge.id(), minX, maxX)
          .isBetween(minX - ATTACHED_MARGIN, maxX + ATTACHED_MARGIN);
    }
  }

  private static LayoutResult groupedInternalFanOut() {
    List<LaidOutNode> nodes =
        List.of(
            new LaidOutNode(
                "order-service",
                "order-service",
                "order-service",
                36.0,
                48.8,
                160.0,
                80.0,
                "Order Service"),
            new LaidOutNode(
                "catalog-service",
                "catalog-service",
                "catalog-service",
                362.0,
                36.0,
                160.0,
                80.0,
                "Catalog Service"),
            new LaidOutNode(
                "payment-service",
                "payment-service",
                "payment-service",
                362.0,
                176.0,
                160.0,
                80.0,
                "Payment Service"),
            new LaidOutNode(
                "fulfillment-service",
                "fulfillment-service",
                "fulfillment-service",
                362.0,
                316.0,
                160.0,
                80.0,
                "Fulfillment Service"));
    List<LaidOutEdge> edges =
        List.of(
            edge(
                "order-checks-catalog",
                "catalog-service",
                List.of(new Point(197.0, 76.0), new Point(361.0, 76.0)),
                "checks catalog"),
            edge(
                "order-requests-payment",
                "payment-service",
                List.of(
                    new Point(197.0, 88.8),
                    new Point(269.0, 88.8),
                    new Point(269.0, 216.0),
                    new Point(361.0, 216.0)),
                "requests payment"),
            edge(
                "order-reserves-stock",
                "fulfillment-service",
                List.of(
                    new Point(197.0, 101.5),
                    new Point(229.0, 101.5),
                    new Point(229.0, 356.0),
                    new Point(361.0, 356.0)),
                "reserves stock"));
    List<LaidOutGroup> groups =
        List.of(
            new LaidOutGroup(
                "core-services",
                "core-services",
                "core-services",
                null,
                12.0,
                12.0,
                534.0,
                408.0,
                List.of(
                    "order-service", "catalog-service", "payment-service", "fulfillment-service"),
                "Core Services"));
    return new LayoutResult("layout-result.schema.v2", "main", nodes, edges, groups, List.of());
  }

  private static LaidOutEdge edge(String id, String target, List<Point> points, String label) {
    return new LaidOutEdge(id, "order-service", target, id, id, List.of(), points, label);
  }
}
