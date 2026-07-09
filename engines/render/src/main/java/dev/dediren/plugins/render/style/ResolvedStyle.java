package dev.dediren.plugins.render.style;

public record ResolvedStyle(
    String backgroundFill,
    Double backgroundFillOpacity,
    String fontFamily,
    double fontSize,
    ResolvedNodeStyle node,
    ResolvedEdgeStyle edge,
    ResolvedGroupStyle group) {}
