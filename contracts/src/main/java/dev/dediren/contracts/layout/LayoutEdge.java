package dev.dediren.contracts.layout;

public record LayoutEdge(
    String id,
    String source,
    String target,
    String label,
    String sourceId,
    String relationshipType,
    LayoutEdgePriority priority) {
  public LayoutEdge(String id, String source, String target, String label, String sourceId) {
    this(id, source, target, label, sourceId, null, null);
  }

  public LayoutEdge(
      String id,
      String source,
      String target,
      String label,
      String sourceId,
      String relationshipType) {
    this(id, source, target, label, sourceId, relationshipType, null);
  }
}
