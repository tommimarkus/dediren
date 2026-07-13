package dev.dediren.plugins.render.style;

import dev.dediren.contracts.render.SvgFontSlant;
import dev.dediren.contracts.render.SvgFontWeight;

public record ResolvedStyle(
    String backgroundFill,
    Double backgroundFillOpacity,
    String fontFamily,
    double fontSize,
    SvgFontWeight fontWeight,
    SvgFontSlant fontStyle,
    ResolvedNodeStyle node,
    ResolvedEdgeStyle edge,
    ResolvedGroupStyle group) {}
