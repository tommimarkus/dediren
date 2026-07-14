package dev.dediren.plugins.render.svg;

import static org.assertj.core.api.Assertions.assertThat;

import dev.dediren.contracts.layout.Point;
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
}
