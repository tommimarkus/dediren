package dev.dediren.contracts.source;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;

public record GenericGraphViewGroup(
    String id,
    String label,
    List<String> members,
    GenericGraphViewGroupRole role,
    String semanticSourceId) {
  public GenericGraphViewGroup {
    members = listOrEmpty(members);
    role = role == null ? GenericGraphViewGroupRole.SEMANTIC_BOUNDARY : role;
  }
}
