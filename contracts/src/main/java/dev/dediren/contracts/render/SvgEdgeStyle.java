package dev.dediren.contracts.render;

import dev.dediren.contracts.util.ContractCollections;
import java.util.List;

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
    SvgEdgeLabelPresentation labelPresentation,
    Double strokeOpacity,
    List<Double> dashPattern) {
  public SvgEdgeStyle {
    dashPattern = ContractCollections.copyOrNull(dashPattern);
  }
}
