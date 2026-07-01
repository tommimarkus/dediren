package dev.dediren.contracts.layout;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.Diagnostic;
import java.util.List;

public record LayoutResult(
    String layoutResultSchemaVersion,
    String viewId,
    List<LaidOutNode> nodes,
    List<LaidOutEdge> edges,
    List<LaidOutGroup> groups,
    List<Diagnostic> warnings) {
  public LayoutResult {
    nodes = listOrEmpty(nodes);
    edges = listOrEmpty(edges);
    groups = listOrEmpty(groups);
    warnings = listOrEmpty(warnings);
  }
}
