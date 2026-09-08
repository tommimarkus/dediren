package dev.dediren.plugins.render.svg;

import static dev.dediren.ir.RouteGeometry.flatten;
import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.CubicBezierRoute;
import dev.dediren.contracts.layout.CubicBezierSegment;
import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.LaidOutGroup;
import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.layout.LayoutResult;
import dev.dediren.contracts.layout.Point;
import dev.dediren.contracts.layout.PolylineRoute;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
          SvgEdgeLabelPresentation.OUTLINE,
          null,
          null,
          null);

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

      double minX = flatten(edge.route()).stream().mapToDouble(Point::x).min().orElseThrow();
      double maxX = flatten(edge.route()).stream().mapToDouble(Point::x).max().orElseThrow();
      double minY = flatten(edge.route()).stream().mapToDouble(Point::y).min().orElseThrow();
      double maxY = flatten(edge.route()).stream().mapToDouble(Point::y).max().orElseThrow();

      assertThat(label.y())
          .as("edge %s label y must stay beside its route [%.1f, %.1f]", edge.id(), minY, maxY)
          .isBetween(minY - ATTACHED_MARGIN, maxY + ATTACHED_MARGIN);
      assertThat(label.x())
          .as("edge %s label x must stay beside its route [%.1f, %.1f]", edge.id(), minX, maxX)
          .isBetween(minX - ATTACHED_MARGIN, maxX + ATTACHED_MARGIN);
    }
  }

  @Test
  void labelUsesTheUsableLongRunInsteadOfATooShortPreferredStartRun() {
    LaidOutEdge edge =
        edge(
            "u-turn",
            "target",
            List.of(
                new Point(0.0, 0.0),
                new Point(20.0, 0.0),
                new Point(20.0, 100.0),
                new Point(300.0, 100.0)),
            "relationship label with enough width");
    double fontSize = EdgeRenderer.edgeLabelFontSize(BASE_FONT_SIZE);

    EdgeLabel label = EdgeRenderer.edgeLabel(edge, DEFAULT_STYLE, List.of(), fontSize);
    LabelBox visible = EdgeRenderer.edgeLabelVisibleBox(label, DEFAULT_STYLE.labelPresentation());

    assertThat(visible.minX())
        .as("the label must be carried by the long final route run, not spill past the short start")
        .isGreaterThanOrEqualTo(20.0);
    assertThat(visible.maxX()).isLessThanOrEqualTo(300.0);
    assertThat(label.y()).isBetween(60.0, 140.0);
  }

  @Test
  void materialAndImprovisationBranchLabelStaysWithItsOwnerAndClearsNearbyBranches() {
    // Firefox acceptance found this exact native-routing shape in the material-and-improvisation
    // view. The three long vertical runs are only 25–56 units apart, so blindly using the first
    // horizontal run can place "needs a check ruling" into the two neighboring branches.
    LaidOutEdge needsCheck =
        edge(
            "mi-flow-needs-check",
            "mi-action-skill-ruling",
            List.of(
                new Point(930.0, 693.0),
                new Point(930.0, 895.0),
                new Point(854.0, 895.0),
                new Point(854.0, 919.0)),
            "needs a check ruling");
    LaidOutEdge needsAction =
        edge(
            "mi-flow-needs-action",
            "mi-action-rule-uncovered",
            List.of(
                new Point(955.0, 693.0),
                new Point(955.0, 717.0),
                new Point(958.0, 717.0),
                new Point(958.0, 1025.0),
                new Point(882.0, 1025.0),
                new Point(882.0, 1049.0)),
            "act outside the rules");
    LaidOutEdge needsAdjust =
        edge(
            "mi-flow-needs-adjust",
            "mi-action-adjust-encounter",
            List.of(
                new Point(980.0, 693.0),
                new Point(980.0, 717.0),
                new Point(986.0, 717.0),
                new Point(986.0, 1179.0)),
            "fight off its difficulty");
    double fontSize = EdgeRenderer.edgeLabelFontSize(BASE_FONT_SIZE);

    EdgeLabel label =
        EdgeRenderer.edgeLabel(
            needsCheck,
            DEFAULT_STYLE,
            Geometry.edgeRouteObstacleBoxes(List.of(needsAction, needsAdjust)),
            fontSize);
    LabelBox visible = EdgeRenderer.edgeLabelVisibleBox(label, DEFAULT_STYLE.labelPresentation());

    assertThat(visible.overlaps(new LabelBox(955.0, 693.0, 958.0, 1025.0)))
        .as("the label must clear the adjacent action branch")
        .isFalse();
    assertThat(Math.abs(label.x() - 930.0))
        .as("the label remains closer to its 930-unit owner branch than the 955-unit competitor")
        .isLessThan(Math.abs(label.x() - 955.0));
  }

  @Test
  void cubicRouteLabelUsesItsMiddleRunRatherThanTheRouteStartPoint() {
    LaidOutEdge edge =
        new LaidOutEdge(
            "curve",
            "source",
            "target",
            "curve",
            "curve",
            List.of(),
            new CubicBezierRoute(
                new Point(0, 0),
                List.of(
                    new CubicBezierSegment(
                        new Point(0, 100), new Point(100, 100), new Point(100, 0)))),
            "curved relationship");

    EdgeLabel label =
        EdgeRenderer.edgeLabel(
            edge, DEFAULT_STYLE, List.of(), EdgeRenderer.edgeLabelFontSize(BASE_FONT_SIZE));

    assertThat(label.x()).isBetween(35.0, 65.0);
    assertThat(label.y()).isBetween(35.0, 115.0);
  }

  @Test
  void diagonalLabelKeepsItsPaintedBoxTwoToSixUnitsFromTheActualRoute() {
    LaidOutEdge edge =
        edge(
            "diagonal",
            "target",
            List.of(new Point(0.0, 0.0), new Point(160.0, 160.0)),
            "diagonal relationship");

    EdgeLabel label =
        EdgeRenderer.edgeLabel(
            edge, DEFAULT_STYLE, List.of(), EdgeRenderer.edgeLabelFontSize(BASE_FONT_SIZE));
    LabelBox visible = EdgeRenderer.edgeLabelVisibleBox(label, DEFAULT_STYLE.labelPresentation());

    assertThat(boxToSegmentDistance(visible, new Point(0.0, 0.0), new Point(160.0, 160.0)))
        .as("the label's painted box must visibly attach to its diagonal owner route")
        .isBetween(2.0, 6.0);
  }

  @Test
  void blockedNearbyCandidatesDoNotSilentlyEscalateToARemoteOffset() {
    LaidOutEdge edge =
        edge(
            "blocked",
            "target",
            List.of(new Point(0.0, 100.0), new Point(300.0, 100.0)),
            "blocked relationship");
    // This obstacle blocks every normal placement in the owner-facing band, while room exists
    // much further away. The renderer must retain the nearest least-overlap attached candidate
    // and publish its constrained status rather than treating remote whitespace as a placement.
    LabelBox blocker = new LabelBox(-100.0, 20.0, 400.0, 180.0);

    EdgeLabel label =
        EdgeRenderer.edgeLabel(
            edge, DEFAULT_STYLE, List.of(blocker), EdgeRenderer.edgeLabelFontSize(BASE_FONT_SIZE));
    LabelBox visible = EdgeRenderer.edgeLabelVisibleBox(label, DEFAULT_STYLE.labelPresentation());

    assertThat(Math.min(Math.abs(visible.minY() - 100.0), Math.abs(visible.maxY() - 100.0)))
        .as("fallback remains visibly attached to its owner route")
        .isBetween(2.0, 6.0);
  }

  @Test
  void labelAvoidsAnotherNearbyRunOfItsOwnURoute() {
    LaidOutEdge owner =
        edge(
            "owner-u",
            "target",
            List.of(
                new Point(0.0, 80.0),
                new Point(0.0, 100.0),
                new Point(300.0, 100.0),
                new Point(300.0, 80.0),
                new Point(0.0, 80.0)),
            "owner relationship");

    EdgeLabel label =
        EdgeRenderer.edgeLabel(
            owner, DEFAULT_STYLE, List.of(), EdgeRenderer.edgeLabelFontSize(BASE_FONT_SIZE));
    LabelBox visible = EdgeRenderer.edgeLabelVisibleBox(label, DEFAULT_STYLE.labelPresentation());

    assertThat(visible.minY())
        .as("the label must not paint through a different nearby run of its owner route")
        .isGreaterThanOrEqualTo(102.0);
  }

  @Test
  void labelRejectsAnAttachedCandidateWhenACompetingRouteIsNearer() {
    LaidOutEdge owner =
        edge(
            "owner",
            "target",
            List.of(new Point(0, 100), new Point(300, 100)),
            "owner relationship");
    LaidOutEdge competitor =
        edge("competitor", "target", List.of(new Point(0, 69), new Point(300, 69)), "");

    ResolvedEdgeStyle style = backgroundStyle(SvgEdgeLabelHorizontalSide.ABOVE);
    EdgeLabel label =
        EdgeRenderer.edgeLabel(
            owner,
            style,
            List.of(),
            List.of(competitor),
            EdgeRenderer.edgeLabelFontSize(BASE_FONT_SIZE));
    LabelBox visible = EdgeRenderer.edgeLabelVisibleBox(label, style.labelPresentation());

    assertThat(visible.minY()).isGreaterThanOrEqualTo(102.0);
  }

  @ParameterizedTest(name = "{0} background clears route")
  @CsvSource({"ABOVE, true", "BELOW, false"})
  void backgroundLabelsClearHorizontalRoutesOnTheRequestedSide(
      SvgEdgeLabelHorizontalSide side, boolean expectedAbove) {
    LaidOutEdge edge =
        edge(
            "horizontal-label",
            "target",
            List.of(new Point(0.0, 100.0), new Point(200.0, 100.0)),
            "label");
    double fontSize = EdgeRenderer.edgeLabelFontSize(BASE_FONT_SIZE);

    ResolvedEdgeStyle style = backgroundStyle(side);
    EdgeLabel label = EdgeRenderer.edgeLabel(edge, style, List.of(), fontSize);

    assertThat(label.y() < 100.0)
        .as("the unobstructed label must remain on the requested %s side", side)
        .isEqualTo(expectedAbove);
    assertClearsHorizontalRoute(label, style, 100.0);
  }

  @ParameterizedTest(name = "blocked {0} background falls back clear")
  @CsvSource({"ABOVE, -1000.0, 100.0, false", "BELOW, 100.0, 1000.0, true"})
  void backgroundLabelFallbackClearsHorizontalRouteOnTheOppositeSide(
      SvgEdgeLabelHorizontalSide preferredSide,
      double blockerMinY,
      double blockerMaxY,
      boolean expectedAbove) {
    LaidOutEdge edge =
        edge(
            "blocked-horizontal-label",
            "target",
            List.of(new Point(0.0, 100.0), new Point(200.0, 100.0)),
            "blocked label");
    double fontSize = EdgeRenderer.edgeLabelFontSize(BASE_FONT_SIZE);

    ResolvedEdgeStyle style = backgroundStyle(preferredSide);
    LabelBox preferredSideBlocker = new LabelBox(-1000.0, blockerMinY, 1000.0, blockerMaxY);

    EdgeLabel label = EdgeRenderer.edgeLabel(edge, style, List.of(preferredSideBlocker), fontSize);

    assertThat(label.y() < 100.0)
        .as("a blocked %s label must fall back to the opposite side", preferredSide)
        .isEqualTo(expectedAbove);
    assertClearsHorizontalRoute(label, style, 100.0);
  }

  private static ResolvedEdgeStyle backgroundStyle(SvgEdgeLabelHorizontalSide side) {
    return new ResolvedEdgeStyle(
        "#64748b",
        2.0,
        "#374151",
        SvgEdgeLineStyle.SOLID,
        SvgEdgeMarkerEnd.NONE,
        SvgEdgeMarkerEnd.FILLED_ARROW,
        SvgEdgeLabelHorizontalPosition.CENTER,
        side,
        SvgEdgeLabelVerticalPosition.CENTER,
        SvgEdgeLabelVerticalSide.LEFT,
        SvgEdgeLabelPresentation.BACKGROUND,
        null,
        null,
        null);
  }

  private static void assertClearsHorizontalRoute(
      EdgeLabel label, ResolvedEdgeStyle style, double routeY) {
    LabelBox visibleBox = EdgeRenderer.edgeLabelVisibleBox(label, style.labelPresentation());
    double routeHalfWidth = style.strokeWidth() / 2.0;
    double visibleGap = Math.max(routeY - visibleBox.maxY(), visibleBox.minY() - routeY);

    assertThat(visibleGap)
        .as("the visible label box must not overlap the route's painted stroke")
        .isGreaterThan(routeHalfWidth);
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
    return new LayoutResult("layout-result.schema.v3", "main", nodes, edges, groups, List.of());
  }

  private static LaidOutEdge edge(String id, String target, List<Point> points, String label) {
    return new LaidOutEdge(
        id, "order-service", target, id, id, List.of(), new PolylineRoute(points), label);
  }

  private static double boxToSegmentDistance(LabelBox box, Point start, Point end) {
    if (segmentTouchesBox(start, end, box)) {
      return 0.0;
    }
    return List.of(
            pointToSegmentDistance(new Point(box.minX(), box.minY()), start, end),
            pointToSegmentDistance(new Point(box.minX(), box.maxY()), start, end),
            pointToSegmentDistance(new Point(box.maxX(), box.minY()), start, end),
            pointToSegmentDistance(new Point(box.maxX(), box.maxY()), start, end))
        .stream()
        .mapToDouble(Double::doubleValue)
        .min()
        .orElseThrow();
  }

  private static boolean segmentTouchesBox(Point start, Point end, LabelBox box) {
    return segmentIntersects(
            start, end, new Point(box.minX(), box.minY()), new Point(box.maxX(), box.minY()))
        || segmentIntersects(
            start, end, new Point(box.maxX(), box.minY()), new Point(box.maxX(), box.maxY()))
        || segmentIntersects(
            start, end, new Point(box.maxX(), box.maxY()), new Point(box.minX(), box.maxY()))
        || segmentIntersects(
            start, end, new Point(box.minX(), box.maxY()), new Point(box.minX(), box.minY()));
  }

  private static boolean segmentIntersects(Point a, Point b, Point c, Point d) {
    return orientation(a, b, c) * orientation(a, b, d) <= 0.0
        && orientation(c, d, a) * orientation(c, d, b) <= 0.0;
  }

  private static double orientation(Point a, Point b, Point c) {
    return (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
  }

  private static double pointToSegmentDistance(Point point, Point start, Point end) {
    double dx = end.x() - start.x();
    double dy = end.y() - start.y();
    double lengthSquared = dx * dx + dy * dy;
    double t =
        lengthSquared == 0.0
            ? 0.0
            : ((point.x() - start.x()) * dx + (point.y() - start.y()) * dy) / lengthSquared;
    t = Math.max(0.0, Math.min(1.0, t));
    return Math.hypot(point.x() - (start.x() + t * dx), point.y() - (start.y() + t * dy));
  }
}
