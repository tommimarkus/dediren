package dev.dediren.plugins.render.style;

import dev.dediren.contracts.render.SvgEdgeLabelHorizontalPosition;
import dev.dediren.contracts.render.SvgEdgeLabelHorizontalSide;
import dev.dediren.contracts.render.SvgEdgeLabelPresentation;
import dev.dediren.contracts.render.SvgEdgeLabelVerticalPosition;
import dev.dediren.contracts.render.SvgEdgeLabelVerticalSide;
import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgEdgeMarkerEnd;
import dev.dediren.contracts.util.ContractCollections;
import java.util.List;

public record ResolvedEdgeStyle(
    String stroke,
    double strokeWidth,
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
    List<Double> dashPattern,
    Double labelOpacity) {
  public ResolvedEdgeStyle {
    dashPattern = ContractCollections.copyOrNull(dashPattern);
  }
}
