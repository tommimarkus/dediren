package dev.dediren.contracts.layout;

public record LayoutEdge(
    String id,
    String source,
    String target,
    String label,
    String sourceId,
    String relationshipType,
    LayoutEdgePriority priority,
    String sourcePointer) {
  public LayoutEdge(String id, String source, String target, String label, String sourceId) {
    this(id, source, target, label, sourceId, null, null, null);
  }

  public LayoutEdge(
      String id,
      String source,
      String target,
      String label,
      String sourceId,
      String relationshipType) {
    this(id, source, target, label, sourceId, relationshipType, null, null);
  }

  public LayoutEdge(
      String id,
      String source,
      String target,
      String label,
      String sourceId,
      String relationshipType,
      LayoutEdgePriority priority) {
    this(id, source, target, label, sourceId, relationshipType, priority, null);
  }
}
