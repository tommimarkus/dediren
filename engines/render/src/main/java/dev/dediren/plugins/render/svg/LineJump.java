package dev.dediren.plugins.render.svg;

import dev.dediren.contracts.layout.Point;
import java.util.Locale;

public record LineJump(int segmentIndex, double x, double y, boolean vertical) {

  // How far back along the crossed segment the route leaves the straight line, and how far to the
  // outside the quadratic's control point sits. One value for the emitted path, the backdrop mask,
  // and the box the viewBox grows to hold: a jump that bulged further than it measured is exactly
  // the clipping this record is now measured to prevent.
  private static final double REACH = 6.0;

  String pathPrefix(Point start, Point end) {
    if (vertical) {
      double before = y + (start.y() < end.y() ? -REACH : REACH);
      double after = y + (start.y() < end.y() ? REACH : -REACH);
      double controlX = x + REACH;
      return String.format(
          Locale.ROOT, "L %.1f %.1f Q %.1f %.1f %.1f %.1f", x, before, controlX, y, x, after);
    }
    double before = x + (start.x() < end.x() ? -REACH : REACH);
    double after = x + (start.x() < end.x() ? REACH : -REACH);
    double controlY = y - REACH;
    return String.format(
        Locale.ROOT, "L %.1f %.1f Q %.1f %.1f %.1f %.1f", before, y, x, controlY, after, y);
  }

  String maskPath() {
    if (vertical) {
      return String.format(
          Locale.ROOT,
          "M %.1f %.1f Q %.1f %.1f %.1f %.1f",
          x,
          y - REACH,
          x + REACH,
          y,
          x,
          y + REACH);
    }
    return String.format(
        Locale.ROOT, "M %.1f %.1f Q %.1f %.1f %.1f %.1f", x - REACH, y, x, y - REACH, x + REACH, y);
  }

  /**
   * The box the route's own arc inks, before the edge's stroke width is added to it.
   *
   * <p>The apex is half a {@link #REACH}, not a whole one: a quadratic reaches only halfway to its
   * control point (B(0.5) = P0/4 + C/2 + P2/4, and both endpoints sit on the straight route). The
   * control point itself would be the convex-hull bound, which would grow the viewBox by twice the
   * ink the jump actually lays down.
   */
  public LabelBox routeInkBox() {
    double apex = REACH / 2.0;
    if (vertical) {
      return new LabelBox(x, y - REACH, x + apex, y + REACH);
    }
    return new LabelBox(x - REACH, y - apex, x + REACH, y);
  }
}
