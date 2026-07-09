package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.layout.GroupProvenance;
import java.util.List;

/** A pre-layout grouping. Provenance uses the existing {@link GroupProvenance} tagged value. */
public record SceneGroup(
    String id, String label, List<String> members, GroupProvenance provenance) {
  public SceneGroup {
    members = listOrEmpty(members);
  }
}
