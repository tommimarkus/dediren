package dev.dediren.contracts.layout;

public record LayoutNode(
    String id,
    String label,
    String sourceId,
    Double widthHint,
    Double heightHint,
    String role,
    Integer partition,
    LayoutLayerConstraint layerConstraint,
    String sourcePointer) {

  public LayoutNode(String id, String label, String sourceId, Double widthHint, Double heightHint) {
    this(id, label, sourceId, widthHint, heightHint, null, null, null, null);
  }

  public LayoutNode(
      String id, String label, String sourceId, Double widthHint, Double heightHint, String role) {
    this(id, label, sourceId, widthHint, heightHint, role, null, null, null);
  }

  public LayoutNode(
      String id,
      String label,
      String sourceId,
      Double widthHint,
      Double heightHint,
      String role,
      Integer partition,
      LayoutLayerConstraint layerConstraint) {
    this(id, label, sourceId, widthHint, heightHint, role, partition, layerConstraint, null);
  }
}
