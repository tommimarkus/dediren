package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.Diagnostic;
import java.util.List;

/** The post-layout scene containing placed nodes, routed edges, and groups with diagnostics. */
public record LaidOutScene(
    String viewId,
    List<PlacedNode> nodes,
    List<RoutedEdge> edges,
    List<PlacedGroup> groups,
    List<Diagnostic> warnings) {
  public LaidOutScene {
    nodes = listOrEmpty(nodes);
    edges = listOrEmpty(edges);
    groups = listOrEmpty(groups);
    warnings = listOrEmpty(warnings);
  }
}
