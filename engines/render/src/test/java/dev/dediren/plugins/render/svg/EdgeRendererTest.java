package dev.dediren.plugins.render.svg;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.LaidOutEdge;
import dev.dediren.contracts.layout.Point;
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
            List.of(
                new Point(674.0, 861.0),
                new Point(704.0, 861.0),
                new Point(704.0, 878.0),
                new Point(674.0, 878.0)),
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
