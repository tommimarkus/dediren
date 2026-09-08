package dev.dediren.contracts.layout;

import java.util.List;

/** A route composed of straight segments joining consecutive points. */
public record PolylineRoute(List<Point> points) implements EdgeRoute {
  public PolylineRoute {
    points = points == null ? List.of() : List.copyOf(points);
  }
}
