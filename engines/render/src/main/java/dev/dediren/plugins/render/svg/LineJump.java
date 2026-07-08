package dev.dediren.plugins.render.svg;

import dev.dediren.contracts.layout.Point;
import java.util.Locale;

public record LineJump(int segmentIndex, double x, double y, boolean vertical) {
  String pathPrefix(Point start, Point end) {
    if (vertical) {
      double before = y + (start.y() < end.y() ? -6.0 : 6.0);
      double after = y + (start.y() < end.y() ? 6.0 : -6.0);
      double controlX = x + 6.0;
      return String.format(
          Locale.ROOT, "L %.1f %.1f Q %.1f %.1f %.1f %.1f", x, before, controlX, y, x, after);
    }
    double before = x + (start.x() < end.x() ? -6.0 : 6.0);
    double after = x + (start.x() < end.x() ? 6.0 : -6.0);
    double controlY = y - 6.0;
    return String.format(
        Locale.ROOT, "L %.1f %.1f Q %.1f %.1f %.1f %.1f", before, y, x, controlY, after, y);
  }

  String maskPath() {
    if (vertical) {
      return String.format(
          Locale.ROOT, "M %.1f %.1f Q %.1f %.1f %.1f %.1f", x, y - 6.0, x + 6.0, y, x, y + 6.0);
    }
    return String.format(
        Locale.ROOT, "M %.1f %.1f Q %.1f %.1f %.1f %.1f", x - 6.0, y, x, y - 6.0, x + 6.0, y);
  }
}
