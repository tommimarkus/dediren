package dev.dediren.plugins.mermaid;

import dev.dediren.contracts.layout.LayoutDirection;
import java.util.List;
import java.util.Map;

record ParsedDiagram(
    LayoutDirection direction,
    List<ParsedNode> nodes,
    List<ParsedEdge> edges,
    List<ParsedGroup> groups,
    Map<String, Integer> ignoredHints) {}

record ParsedNode(String id, String label) {}

record ParsedEdge(String source, String target, String label, EdgeKind kind) {}

enum EdgeKind {
  DIRECTED,
  UNDIRECTED
}

record ParsedGroup(String id, String label, List<String> members) {}
