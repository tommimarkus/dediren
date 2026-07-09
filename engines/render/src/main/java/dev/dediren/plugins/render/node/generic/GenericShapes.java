package dev.dediren.plugins.render.node.generic;

import static dev.dediren.plugins.render.svg.Svg.attr;
import static dev.dediren.plugins.render.svg.Svg.styleNumber;

import dev.dediren.contracts.layout.LaidOutNode;
import dev.dediren.contracts.render.SvgNodeShape;
import dev.dediren.plugins.render.style.ResolvedNodeStyle;
import java.util.Locale;

/**
 * Free-form node geometry for generic (decorator-less) nodes. The render policy selects a {@link
 * SvgNodeShape}; this builder emits the matching SVG element, each carrying {@code
 * data-dediren-node-shape="<name>"} and the resolved {@code fill}/{@code stroke}/{@code
 * stroke-width}. Mirrors the per-notation shape builders (e.g. {@code ArchimateShapes}); reached
 * only from {@code SvgDocument.nodeShape} when a node has no ArchiMate/UML decorator.
 */
public final class GenericShapes {

  private GenericShapes() {}

  public static String genericNodeShape(LaidOutNode node, ResolvedNodeStyle style) {
    SvgNodeShape shape = style.shape();
    return switch (shape) {
      case RECTANGLE -> rect(node, style, shape, 0.0);
      case ROUNDED_RECTANGLE -> rect(node, style, shape, style.rx());
      case STADIUM -> rect(node, style, shape, Math.min(node.width(), node.height()) / 2.0);
      case ELLIPSE -> ellipse(node, style, shape);
      case CIRCLE -> circle(node, style, shape);
      case DIAMOND -> diamond(node, style, shape);
      case HEXAGON -> hexagon(node, style, shape);
      case PARALLELOGRAM -> parallelogram(node, style, shape);
      case TRIANGLE -> triangle(node, style, shape);
      case CYLINDER -> cylinder(node, style, shape);
    };
  }

  private static String name(SvgNodeShape shape) {
    return shape.name().toLowerCase(Locale.ROOT);
  }

  private static String rect(
      LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape, double rx) {
    return String.format(
        Locale.ROOT,
        "<rect data-dediren-node-shape=\"%s\" x=\"%.1f\" y=\"%.1f\" width=\"%.1f\" height=\"%.1f\" rx=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        name(shape),
        node.x(),
        node.y(),
        node.width(),
        node.height(),
        styleNumber(rx),
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }

  private static String ellipse(LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    return String.format(
        Locale.ROOT,
        "<ellipse data-dediren-node-shape=\"%s\" cx=\"%.1f\" cy=\"%.1f\" rx=\"%.1f\" ry=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        name(shape),
        node.x() + node.width() / 2.0,
        node.y() + node.height() / 2.0,
        node.width() / 2.0,
        node.height() / 2.0,
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }

  private static String circle(LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    return String.format(
        Locale.ROOT,
        "<circle data-dediren-node-shape=\"%s\" cx=\"%.1f\" cy=\"%.1f\" r=\"%.1f\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        name(shape),
        node.x() + node.width() / 2.0,
        node.y() + node.height() / 2.0,
        Math.min(node.width(), node.height()) / 2.0,
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }

  private static String diamond(LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    double cx = node.x() + node.width() / 2.0;
    double cy = node.y() + node.height() / 2.0;
    String points =
        String.format(
            Locale.ROOT,
            "%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
            cx,
            node.y(),
            node.x() + node.width(),
            cy,
            cx,
            node.y() + node.height(),
            node.x(),
            cy);
    return polygon(shape, points, style);
  }

  private static String hexagon(LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    double cy = node.y() + node.height() / 2.0;
    double inset = Math.min(node.width() * 0.25, node.height() / 2.0);
    String points =
        String.format(
            Locale.ROOT,
            "%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
            node.x() + inset,
            node.y(),
            node.x() + node.width() - inset,
            node.y(),
            node.x() + node.width(),
            cy,
            node.x() + node.width() - inset,
            node.y() + node.height(),
            node.x() + inset,
            node.y() + node.height(),
            node.x(),
            cy);
    return polygon(shape, points, style);
  }

  private static String parallelogram(
      LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    double slant = Math.min(node.width() * 0.25, node.height());
    String points =
        String.format(
            Locale.ROOT,
            "%.1f,%.1f %.1f,%.1f %.1f,%.1f %.1f,%.1f",
            node.x() + slant,
            node.y(),
            node.x() + node.width(),
            node.y(),
            node.x() + node.width() - slant,
            node.y() + node.height(),
            node.x(),
            node.y() + node.height());
    return polygon(shape, points, style);
  }

  private static String triangle(LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    String points =
        String.format(
            Locale.ROOT,
            "%.1f,%.1f %.1f,%.1f %.1f,%.1f",
            node.x() + node.width() / 2.0,
            node.y(),
            node.x() + node.width(),
            node.y() + node.height(),
            node.x(),
            node.y() + node.height());
    return polygon(shape, points, style);
  }

  private static String polygon(SvgNodeShape shape, String points, ResolvedNodeStyle style) {
    return String.format(
        Locale.ROOT,
        "<polygon data-dediren-node-shape=\"%s\" points=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        name(shape),
        points,
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }

  private static String cylinder(LaidOutNode node, ResolvedNodeStyle style, SvgNodeShape shape) {
    double rx = node.width() / 2.0;
    double ry = Math.min(node.height() * 0.16, node.width() / 2.0);
    double top = node.y() + ry;
    double bottom = node.y() + node.height() - ry;
    // Body (both sides + front bottom curve) then the visible top-cap front curve, as one path so
    // the shape stays a single element carrying the data attribute.
    String d =
        String.format(
            Locale.ROOT,
            "M %.1f %.1f A %.1f %.1f 0 0 0 %.1f %.1f L %.1f %.1f A %.1f %.1f 0 0 0 %.1f %.1f Z M %.1f %.1f A %.1f %.1f 0 0 1 %.1f %.1f",
            node.x(),
            top,
            rx,
            ry,
            node.x() + node.width(),
            top,
            node.x() + node.width(),
            bottom,
            rx,
            ry,
            node.x(),
            bottom,
            node.x(),
            top,
            rx,
            ry,
            node.x() + node.width(),
            top);
    return String.format(
        Locale.ROOT,
        "<path data-dediren-node-shape=\"%s\" d=\"%s\" fill=\"%s\" stroke=\"%s\" stroke-width=\"%s\"/>",
        name(shape),
        d,
        attr(style.fill()),
        attr(style.stroke()),
        styleNumber(style.strokeWidth()));
  }
}
