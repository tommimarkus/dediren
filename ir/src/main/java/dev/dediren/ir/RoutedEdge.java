package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.layout.EdgeRoute;
import java.util.List;

/** A post-layout edge with typed route geometry and origin. */
public record RoutedEdge(
    String id,
    String source,
    String target,
    String sourceId,
    String projectionId,
    List<String> routingHints,
    EdgeRoute route,
    String label,
    SourcePointer origin) {
  public RoutedEdge {
    routingHints = listOrEmpty(routingHints);
  }
}
