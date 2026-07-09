package dev.dediren.plugins.render.style;

import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgFontSlant;
import dev.dediren.contracts.render.SvgFontWeight;
import dev.dediren.contracts.render.SvgLabelAlign;
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
    List<Double> dashPattern,
    SvgFontWeight fontWeight,
    SvgFontSlant fontStyle,
    String fontFamily,
    SvgLabelAlign labelAlign,
    Double labelOpacity) {
  public ResolvedNodeStyle {
    dashPattern = ContractCollections.copyOrNull(dashPattern);
  }
}
