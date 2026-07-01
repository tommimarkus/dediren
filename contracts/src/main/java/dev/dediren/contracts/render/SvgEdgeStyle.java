package dev.dediren.contracts.render;

public record SvgEdgeStyle(
    String stroke,
    Double strokeWidth,
    String labelFill,
    SvgEdgeLineStyle lineStyle,
    SvgEdgeMarkerEnd markerStart,
    SvgEdgeMarkerEnd markerEnd,
    SvgEdgeLabelHorizontalPosition labelHorizontalPosition,
    SvgEdgeLabelHorizontalSide labelHorizontalSide,
    SvgEdgeLabelVerticalPosition labelVerticalPosition,
    SvgEdgeLabelVerticalSide labelVerticalSide,
    SvgEdgeLabelPresentation labelPresentation) {}
