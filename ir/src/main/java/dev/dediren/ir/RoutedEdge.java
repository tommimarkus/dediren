package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.layout.Point;
import java.util.List;

/** A post-layout routed edge with points and origin. */
public record RoutedEdge(
    String id,
    String source,
    String target,
    String sourceId,
    String projectionId,
    List<String> routingHints,
    List<Point> points,
    String label,
    SourcePointer origin) {
  public RoutedEdge {
    routingHints = listOrEmpty(routingHints);
    points = listOrEmpty(points);
  }
}
