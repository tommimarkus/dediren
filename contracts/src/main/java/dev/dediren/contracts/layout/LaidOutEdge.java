package dev.dediren.contracts.layout;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record LaidOutEdge(
    String id,
    String source,
    String target,
    String sourceId,
    String projectionId,
    List<String> routingHints,
    EdgeRoute route,
    String label,
    String sourcePointer) {
  public LaidOutEdge {
    routingHints = listOrEmpty(routingHints);
  }

  public LaidOutEdge(
      String id,
      String source,
      String target,
      String sourceId,
      String projectionId,
      List<String> routingHints,
      EdgeRoute route,
      String label) {
    this(id, source, target, sourceId, projectionId, routingHints, route, label, null);
  }
}
