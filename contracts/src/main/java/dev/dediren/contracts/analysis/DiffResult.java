package dev.dediren.contracts.analysis;

import static dev.dediren.contracts.util.ContractCollections.listOrEmpty;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * The `dediren diff` payload: what changed between two source-model revisions, keyed on stable ids,
 * every list sorted by id so the output is byte-stable. A diff is a report, never a merge — the
 * command mutates nothing.
 */
public record DiffResult(
    String diffResultSchemaVersion,
    EntityChanges nodes,
    EntityChanges relationships,
    ViewChanges views) {

  public record EntityChanges(
      List<EntityRef> added, List<EntityRef> removed, List<ChangedEntity> changed) {
    public EntityChanges {
      added = listOrEmpty(added);
      removed = listOrEmpty(removed);
      changed = listOrEmpty(changed);
    }
  }

  public record EntityRef(String id, String type, String label) {}

  public record ChangedEntity(String id, List<FieldChange> changes) {
    public ChangedEntity {
      changes = listOrEmpty(changes);
    }
  }

  /**
   * One field-level change: {@code field} is {@code type}, {@code label}, {@code source}, {@code
   * target}, or {@code properties.<top-level-key>} (properties compare shallowly by key); {@code
   * from}/{@code to} carry the JSON values, null meaning absent.
   */
  public record FieldChange(String field, JsonNode from, JsonNode to) {}

  public record ViewChanges(List<String> added, List<String> removed, List<ChangedView> changed) {
    public ViewChanges {
      added = listOrEmpty(added);
      removed = listOrEmpty(removed);
      changed = listOrEmpty(changed);
    }
  }

  public record ChangedView(
      String id,
      List<String> nodesAdded,
      List<String> nodesRemoved,
      List<String> relationshipsAdded,
      List<String> relationshipsRemoved) {
    public ChangedView {
      nodesAdded = listOrEmpty(nodesAdded);
      nodesRemoved = listOrEmpty(nodesRemoved);
      relationshipsAdded = listOrEmpty(relationshipsAdded);
      relationshipsRemoved = listOrEmpty(relationshipsRemoved);
    }
  }
}
