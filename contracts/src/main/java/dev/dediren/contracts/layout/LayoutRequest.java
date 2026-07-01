package dev.dediren.contracts.layout;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record LayoutRequest(
    String layoutRequestSchemaVersion,
    String viewId,
    List<LayoutNode> nodes,
    List<LayoutEdge> edges,
    List<LayoutGroup> groups,
    List<LayoutConstraint> constraints,
    LayoutPreferences layoutPreferences) {
  public LayoutRequest {
    nodes = listOrEmpty(nodes);
    edges = listOrEmpty(edges);
    groups = listOrEmpty(groups);
    constraints = listOrEmpty(constraints);
  }
}
