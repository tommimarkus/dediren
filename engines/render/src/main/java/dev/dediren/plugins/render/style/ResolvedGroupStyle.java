package dev.dediren.plugins.render.style;

import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgNodeDecorator;
import dev.dediren.contracts.util.ContractCollections;
import java.util.List;

public record ResolvedGroupStyle(
    String fill,
    String stroke,
    double strokeWidth,
    double rx,
    String labelFill,
    double labelSize,
    SvgNodeDecorator decorator,
    Double fillOpacity,
    Double strokeOpacity,
    SvgEdgeLineStyle lineStyle,
    List<Double> dashPattern) {
  public ResolvedGroupStyle {
    dashPattern = ContractCollections.copyOrNull(dashPattern);
  }
}
