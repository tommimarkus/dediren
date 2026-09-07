package dev.dediren.contracts.layout;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

/** A route composed of straight segments joining consecutive points. */
public record PolylineRoute(List<Point> points) implements EdgeRoute {
  public PolylineRoute {
    points = listOrEmpty(points);
  }
}
