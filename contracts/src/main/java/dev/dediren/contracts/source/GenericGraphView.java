package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.layout.LayoutPreferences;
import java.util.List;

public record GenericGraphView(
    String id,
    String label,
    GenericGraphViewKind kind,
    List<String> nodes,
    List<String> relationships,
    LayoutPreferences layoutPreferences,
    List<GenericGraphViewGroup> groups) {
  public GenericGraphView {
    nodes = listOrEmpty(nodes);
    relationships = listOrEmpty(relationships);
    groups = listOrEmpty(groups);
  }
}
