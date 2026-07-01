package dev.dediren.contracts.layout;

public record LaidOutNode(
    String id,
    String sourceId,
    String projectionId,
    double x,
    double y,
    double width,
    double height,
    String label,
    String role) {

  public LaidOutNode(
      String id,
      String sourceId,
      String projectionId,
      double x,
      double y,
      double width,
      double height,
      String label) {
    this(id, sourceId, projectionId, x, y, width, height, label, null);
  }
}
