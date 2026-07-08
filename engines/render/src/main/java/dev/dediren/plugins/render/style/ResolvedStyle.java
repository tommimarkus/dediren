package dev.dediren.plugins.render.style;

public record ResolvedStyle(
    String backgroundFill,
    String fontFamily,
    double fontSize,
    ResolvedNodeStyle node,
    ResolvedEdgeStyle edge,
    ResolvedGroupStyle group) {}
