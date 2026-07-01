package dev.dediren.contracts.layout;

public record LayoutEdge(
    String id,
    String source,
    String target,
    String label,
    String sourceId,
    String relationshipType) {
  public LayoutEdge(String id, String source, String target, String label, String sourceId) {
    this(id, source, target, label, sourceId, null);
  }
}
