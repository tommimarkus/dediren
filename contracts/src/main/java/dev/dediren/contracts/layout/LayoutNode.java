package dev.dediren.contracts.layout;

public record LayoutNode(
    String id, String label, String sourceId, Double widthHint, Double heightHint, String role) {

  public LayoutNode(String id, String label, String sourceId, Double widthHint, Double heightHint) {
    this(id, label, sourceId, widthHint, heightHint, null);
  }
}
