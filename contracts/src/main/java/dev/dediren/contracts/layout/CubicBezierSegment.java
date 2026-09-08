package dev.dediren.contracts.layout;

/** One cubic Bezier segment; its start is the route start or the preceding segment end. */
public record CubicBezierSegment(Point control1, Point control2, Point end) {}
