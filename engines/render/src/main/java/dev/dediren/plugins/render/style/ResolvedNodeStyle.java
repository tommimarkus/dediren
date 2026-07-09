package dev.dediren.plugins.render.style;

import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.contracts.render.SvgNodeShape;
import dev.dediren.contracts.util.ContractCollections;
import java.util.List;

public record ResolvedNodeStyle(
    String fill,
    String stroke,
    double strokeWidth,
    double rx,
    String labelFill,
    SvgNodeDecorator decorator,
    SvgNodeShape shape,
    Double fillOpacity,
    Double strokeOpacity,
    SvgEdgeLineStyle lineStyle,
    List<Double> dashPattern) {
  public ResolvedNodeStyle {
    dashPattern = ContractCollections.copyOrNull(dashPattern);
  }
}
