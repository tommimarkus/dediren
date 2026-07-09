package dev.dediren.contracts.render;

import dev.dediren.contracts.util.ContractCollections;
import java.util.List;

public record SvgNodeStyle(
    String fill,
    String stroke,
    Double strokeWidth,
    Double rx,
    String labelFill,
    SvgNodeDecorator decorator,
    SvgNodeShape shape,
    Double fillOpacity,
    Double strokeOpacity,
    SvgEdgeLineStyle lineStyle,
    List<Double> dashPattern) {
  public SvgNodeStyle {
    dashPattern = ContractCollections.copyOrNull(dashPattern);
  }
}
