package dev.dediren.plugins.render.style;

import dev.dediren.contracts.render.SvgEdgeLineStyle;
import dev.dediren.contracts.render.SvgFontSlant;
import dev.dediren.contracts.render.SvgFontWeight;
import dev.dediren.contracts.render.SvgGradient;
import dev.dediren.contracts.render.SvgLabelAlign;
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
    List<Double> dashPattern,
    SvgFontWeight fontWeight,
    SvgFontSlant fontStyle,
    String fontFamily,
    SvgLabelAlign labelAlign,
    Double labelOpacity,
    SvgGradient fillGradient) {
  public ResolvedGroupStyle {
    dashPattern = ContractCollections.copyOrNull(dashPattern);
  }

  /**
   * A copy with a different fill (used to point a gradient-filled group at its {@code url(#…)}).
   */
  public ResolvedGroupStyle withFill(String newFill) {
    return new ResolvedGroupStyle(
        newFill,
        stroke,
        strokeWidth,
        rx,
        labelFill,
        labelSize,
        decorator,
        fillOpacity,
        strokeOpacity,
        lineStyle,
        dashPattern,
        fontWeight,
        fontStyle,
        fontFamily,
        labelAlign,
        labelOpacity,
        fillGradient);
  }
}
