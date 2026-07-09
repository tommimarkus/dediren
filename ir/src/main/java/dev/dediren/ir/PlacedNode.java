package dev.dediren.ir;

/** A post-layout placed node with geometry and origin. */
public record PlacedNode(
    String id,
    String sourceId,
    String projectionId,
    double x,
    double y,
    double width,
    double height,
    String label,
    String role,
    SourcePointer origin) {}
