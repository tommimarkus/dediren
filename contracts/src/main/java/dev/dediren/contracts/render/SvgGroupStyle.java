package dev.dediren.contracts.render;

import dev.dediren.contracts.util.ContractCollections;
import java.util.List;

public record SvgGroupStyle(
    String fill,
    String stroke,
    Double strokeWidth,
    Double rx,
    String labelFill,
    Double labelSize,
    SvgNodeDecorator decorator,
    Double fillOpacity,
    Double strokeOpacity,
    SvgEdgeLineStyle lineStyle,
    List<Double> dashPattern,
    SvgFontWeight fontWeight,
    SvgFontSlant fontStyle,
    String fontFamily,
    SvgLabelAlign labelAlign,
    Double labelOpacity) {
  public SvgGroupStyle {
    dashPattern = ContractCollections.copyOrNull(dashPattern);
  }
}
