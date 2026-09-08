package dev.dediren.plugins.render.svg;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.CubicBezierRoute;
import dev.dediren.contracts.layout.CubicBezierSegment;
import dev.dediren.contracts.layout.LaidOutEdge;
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
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins {@link EdgeRenderer#roundedPathDataWithLineJumps} to bound line-jump emission at BOTH ends
 * of a segment.
 *
 * <p>The filter used to check jumps only against the segment's end progress ({@code
 * rounded.before()}). But the pen does not start drawing a segment's straight run at its geometric
 * start point — it resumes at the PREVIOUS corner's {@code rounded.after()}, up to the corner
 * radius (8px) into the segment. A crossing inside that entry region sits behind where the pen
 * actually is, so emitting a jump there made the rendered path double back on itself.
 */
class EdgeRendererTest {

  @Test
  void jumpMaskMustClearEndpointPaint() {
    LaidOutEdge prior = edge("prior", List.of(new Point(50, 50), new Point(50, 150)));
    LaidOutEdge current = edge("current", List.of(new Point(0, 100), new Point(100, 100)));
    assertThat(
            EdgeRenderer.lineJumps(current, List.of(prior), List.of(new LabelBox(57, 99, 58, 101))))
        .isEmpty();
  }

  @Test
  void rejectedDecoratedJumpDoesNotSuppressItsFeasibleNeighbor() {
    LaidOutEdge prior = edge("prior", List.of(new Point(50, 50), new Point(50, 150)));
    LaidOutEdge next = edge("next", List.of(new Point(60, 50), new Point(60, 150)));
    LaidOutEdge current = edge("current", List.of(new Point(0, 100), new Point(100, 100)));
    assertThat(
            EdgeRenderer.lineJumps(
                current, List.of(prior, next), List.of(new LabelBox(43, 99, 45, 101))))
        .extracting(LineJump::x)
        .containsExactly(60.0);
  }

  @Test
  void jumpInsideRoundedCornerEntryRegionIsDropped() {
    List<Point> points = List.of(new Point(0, 0), new Point(100, 0), new Point(100, 100));
    // Segment 1 runs (100,0)->(100,100); the corner at (100,0) resumes the pen at (100, r).
    // A crossing at y=3 sits inside that entry region and must not be emitted.
    LineJump insideEntry = new LineJump(1, 100.0, 3.0, true);

    assertThat(EdgeRenderer.roundedPathDataWithLineJumps(points, List.of(insideEntry)))
        .isEqualTo(EdgeRenderer.roundedPathData(points));
  }

  @Test
  void jumpInsideRoundedCornerEntryRegionIsDroppedForHorizontalSegment() {
    List<Point> points = List.of(new Point(0, 0), new Point(0, 100), new Point(100, 100));
    // Segment 1 runs (0,100)->(100,100); the corner at (0,100) resumes the pen at (r, 100).
    // A crossing at x=3 sits inside that entry region and must not be emitted.
    LineJump insideEntry = new LineJump(1, 3.0, 100.0, false);

    assertThat(EdgeRenderer.roundedPathDataWithLineJumps(points, List.of(insideEntry)))
        .isEqualTo(EdgeRenderer.roundedPathData(points));
  }

  @Test
  void jumpAtRouteEndpointIsNotAccepted() {
    LaidOutEdge prior = edge("prior", List.of(new Point(0, 100), new Point(100, 100)));
    LaidOutEdge current = edge("current", List.of(new Point(100, 0), new Point(100, 100)));

    assertThat(EdgeRenderer.lineJumps(current, List.of(prior)))
        .as("a six-unit jump needs room on both sides of its crossing")
        .isEmpty();
  }

  @Test
  void neighboringJumpsDoNotOverlapTheirSixUnitReaches() {
    LaidOutEdge firstCrossing = edge("first", List.of(new Point(100, 0), new Point(100, 100)));
    LaidOutEdge secondCrossing = edge("second", List.of(new Point(110, 0), new Point(110, 100)));
    LaidOutEdge current = edge("current", List.of(new Point(0, 50), new Point(200, 50)));

    assertThat(EdgeRenderer.lineJumps(current, List.of(firstCrossing, secondCrossing)))
        .as("the second arc would overlap the first arc's six-unit reach")
        .hasSize(1)
        .first()
        .extracting(LineJump::x)
        .isEqualTo(100.0);
  }

  @Test
  void jumpJustInsideRoundedCornerStraightRunIsAccepted() {
    LaidOutEdge crossing = edge("crossing", List.of(new Point(50, 14), new Point(150, 14)));
    LaidOutEdge current =
        edge("current", List.of(new Point(0, 0), new Point(100, 0), new Point(100, 100)));

    assertThat(EdgeRenderer.lineJumps(current, List.of(crossing))).hasSize(1);
  }

  @Test
  void jumpJustOutsideRoundedCornerStraightRunIsDropped() {
    LaidOutEdge crossing = edge("crossing", List.of(new Point(50, 13), new Point(150, 13)));
    LaidOutEdge current =
        edge("current", List.of(new Point(0, 0), new Point(100, 0), new Point(100, 100)));

    assertThat(EdgeRenderer.lineJumps(current, List.of(crossing))).isEmpty();
  }

  @Test
  void neighboringJumpsWhoseSixUnitReachesOnlyTouchAreBothAccepted() {
    LaidOutEdge firstCrossing = edge("first", List.of(new Point(100, 0), new Point(100, 100)));
    LaidOutEdge secondCrossing = edge("second", List.of(new Point(112, 0), new Point(112, 100)));
    LaidOutEdge current = edge("current", List.of(new Point(0, 50), new Point(200, 50)));

    assertThat(EdgeRenderer.lineJumps(current, List.of(firstCrossing, secondCrossing))).hasSize(2);
  }

  @Test
  void jumpThatWouldPaintIntoAnEndpointDecorationIsDropped() {
    LaidOutEdge crossing = edge("crossing", List.of(new Point(0, 12), new Point(100, 12)));
    LaidOutEdge current = edge("current", List.of(new Point(50, 0), new Point(50, 100)));
    // A start decoration occupies y=0..15 on this vertical route. Although the six-unit reach
    // fits the unrounded segment, the arc at y=12 would compete with that decoration's ink.
    LabelBox startDecoration = new LabelBox(42, -5, 58, 15);

    assertThat(EdgeRenderer.lineJumps(current, List.of(crossing), List.of(startDecoration)))
        .as("accepted jumps and endpoint decorations must never paint over each other")
        .isEmpty();
  }

  @Test
  void cubicOwnersNeverAcceptJumpsThatTheirNativePathCannotDraw() {
    LaidOutEdge crossing = edge("crossing", List.of(new Point(50, 0), new Point(50, 100)));
    LaidOutEdge cubic =
        new LaidOutEdge(
            "cubic",
            "cubic-source",
            "cubic-target",
            "cubic",
            "cubic",
            List.of(),
            new CubicBezierRoute(
                new Point(0, 50),
                List.of(
                    new CubicBezierSegment(
                        new Point(33, 50), new Point(66, 50), new Point(100, 50)))),
            "");

    assertThat(EdgeRenderer.lineJumps(cubic, List.of(crossing)))
        .as("native C path emission ignores jumps, so a cubic may never emit a mask for one")
        .isEmpty();
  }

  @Test
  void sharedSourceHintDoesNotSuppressACrossingAfterRoutesDiverge() {
    LaidOutEdge previous =
        new LaidOutEdge(
            "previous",
            "shared",
            "p-target",
            "previous",
            "previous",
            List.of("shared_source_junction"),
            new PolylineRoute(List.of(new Point(0, 50), new Point(100, 50))),
            "");
    LaidOutEdge current =
        new LaidOutEdge(
            "current",
            "shared",
            "c-target",
            "current",
            "current",
            List.of("shared_source_junction"),
            new PolylineRoute(List.of(new Point(50, 0), new Point(50, 100))),
            "");

    assertThat(EdgeRenderer.lineJumps(current, List.of(previous)))
        .as("the hint only describes the common stub, not later independent route geometry")
        .hasSize(1);
  }

  private static LaidOutEdge edge(String id, List<Point> points) {
    return new LaidOutEdge(
        id, id + "-source", id + "-target", id, id, List.of(), new PolylineRoute(points), "");
  }

  /**
   * Regression for a self-loop that exhausts every placement strategy (hug, on-route vertical,
   * displaced): the {@code encounter.replaced} case, where the node spans x 524..674 / y 806..878
   * and the self-loop's horizontal run sits at y=861, inside the node's own height. Every
   * hug/vertical/displaced candidate is blocked by a surrounding obstacle, so the blind
   * post-cascade fallback returned {@code preferredX, segment.y + baseOffset} (879.0) without
   * checking the obstacle set at all — 1px below the node's bottom edge, so the label's glyph body
   * (font-size reaches upward from its baseline) painted entirely inside the node rect.
   */
  @Test
  void selfLoopLabelFallbackAvoidsItsOwnNodeWhenEveryStrategyIsBlocked() {
    LabelBox nodeBox = new LabelBox(524.0, 806.0, 674.0, 878.0);
    // Covers the y-band every candidate offset lands in, so no candidate is ever fully clear and
    // the placement cascade is forced all the way to the fallback under test.
    LabelBox surroundingBlocker = new LabelBox(400.0, 700.0, 900.0, 1050.0);
    List<LabelBox> occupiedBoxes = List.of(nodeBox, surroundingBlocker);

    LaidOutEdge selfLoop =
        new LaidOutEdge(
            "encounter.replaced",
            "encounter",
            "encounter",
            "encounter.replaced",
            "encounter.replaced",
            List.of(),
            new PolylineRoute(
                List.of(
                    new Point(674.0, 861.0),
                    new Point(704.0, 861.0),
                    new Point(704.0, 878.0),
                    new Point(674.0, 878.0))),
            "encounter.replaced");

    ResolvedEdgeStyle style =
        new ResolvedEdgeStyle(
            "#64748b",
            1.5,
            "#374151",
            SvgEdgeLineStyle.SOLID,
            SvgEdgeMarkerEnd.NONE,
            SvgEdgeMarkerEnd.FILLED_ARROW,
            SvgEdgeLabelHorizontalPosition.CENTER,
            SvgEdgeLabelHorizontalSide.BELOW,
            SvgEdgeLabelVerticalPosition.CENTER,
            SvgEdgeLabelVerticalSide.LEFT,
            SvgEdgeLabelPresentation.OUTLINE,
            null,
            null,
            null);
    double fontSize = EdgeRenderer.edgeLabelFontSize(14.0);

    EdgeLabel label = EdgeRenderer.edgeLabel(selfLoop, style, occupiedBoxes, fontSize);
    LabelBox visibleBox = EdgeRenderer.edgeLabelVisibleBox(label, style.labelPresentation());

    assertThat(visibleBox.overlaps(nodeBox))
        .as(
            "placed label box %s must not paint underneath its own node %s (label y=%.1f)",
            visibleBox, nodeBox, label.y())
        .isFalse();
  }
}
