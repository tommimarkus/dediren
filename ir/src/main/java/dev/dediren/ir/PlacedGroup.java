package dev.dediren.ir;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import dev.dediren.contracts.layout.GroupProvenance;
import java.util.List;

/** A post-layout placed group with geometry and provenance (no origin). */
public record PlacedGroup(
    String id,
    String sourceId,
    String projectionId,
    GroupProvenance provenance,
    double x,
    double y,
    double width,
    double height,
    List<String> members,
    String label) {
  public PlacedGroup {
    members = listOrEmpty(members);
  }
}
