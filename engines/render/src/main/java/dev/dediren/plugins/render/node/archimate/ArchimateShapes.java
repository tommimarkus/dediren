package dev.dediren.plugins.render.node.archimate;

import static dev.dediren.plugins.render.svg.Svg.attr;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import java.util.Locale;

public final class ArchimateShapes {

  private ArchimateShapes() {}

  public static String archimateCutCornerShape(LaidOutNode node, ResolvedNodeStyle style) {
    double corner = Math.max(8.0, Math.min(14.0, Math.min(node.width(), node.height()) * 0.14));
    return String.format(
        Locale.ROOT,
        "<path data-dediren-node-shape=\"archimate_cut_corner_rectangle\" d=\"M %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f L %.1f %.1f Z\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        node.x() + corner,
        node.y(),
        node.x() + node.width() - corner,
        node.y(),
        node.x() + node.width(),
        node.y() + corner,
        node.x() + node.width(),
        node.y() + node.height() - corner,
        node.x() + node.width() - corner,
        node.y() + node.height(),
        node.x() + corner,
        node.y() + node.height(),
        node.x(),
        node.y() + node.height() - corner,
        node.x(),
        node.y() + corner,
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }
}
